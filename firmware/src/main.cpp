// NEXUS Tier 5A/5B — ESP32 temperature sensor + connectivity firmware.
//
// Publishes a real DHT22/DHT11 temperature reading into the EXACT SAME
// telemetry wire contract the Python simulator already uses
// (simulator/src/nexus_simulator/mqtt_publisher.py) - same MQTT topic
// shape, same JSON field names/types, same QoS. The NEXUS backend
// telemetry pipeline (TelemetryMQTTListener/TelemetryRequest/
// TelemetryService) is not modified and does not need to be: it already
// accepts this contract from any transport.
//
// Tier 5B additionally registers an MQTT Last Will and Testament (fires
// "offline", retained, if this device's connection is ever lost
// ungracefully) and publishes a retained "online" birth message after every
// successful connect - see nexus/devices/{deviceId}/status in the backend's
// DeviceStatusMessageHandler for how this is consumed. No command
// subscription, no actuator, no OTA, no device registration/discovery.

#include <Arduino.h>
#include <WiFi.h>
#include <MQTT.h>
#include <ArduinoJson.h>
#include <DHT.h>
#include <time.h>

#include "config.h"

static WiFiClient netClient;
static MQTTClient mqttClient(256); // buffer size in bytes, comfortably fits our small JSON payload
static DHT dht(DHT_PIN, DHT_TYPE);

static String telemetryTopic;
static String deviceStatusTopic;
static String mqttClientId;

static unsigned long lastPublishMillis = 0;
static const unsigned long PUBLISH_INTERVAL_MILLIS = (unsigned long) PUBLISH_INTERVAL_SECONDS * 1000UL;

static const unsigned long WIFI_CONNECT_TIMEOUT_MILLIS = 20000UL;
static const unsigned long MQTT_CONNECT_TIMEOUT_MILLIS = 10000UL;
static const unsigned long TIME_SYNC_TIMEOUT_MILLIS = 20000UL;

// A plausible NTP-synced epoch is well past this value (2024-01-01 UTC);
// anything below it means time() has not been synchronized yet.
static const time_t MIN_PLAUSIBLE_EPOCH = 1704067200;

void connectWiFi() {
    if (WiFi.status() == WL_CONNECTED) {
        return;
    }

    Serial.printf("[WiFi] Connecting to SSID '%s'...\n", WIFI_SSID);
    WiFi.mode(WIFI_STA);
    WiFi.begin(WIFI_SSID, WIFI_PASSWORD);

    unsigned long start = millis();
    while (WiFi.status() != WL_CONNECTED) {
        if (millis() - start > WIFI_CONNECT_TIMEOUT_MILLIS) {
            Serial.println("[WiFi] Connection attempt timed out; will retry.");
            return;
        }
        delay(250);
        Serial.print(".");
    }
    Serial.println();
    Serial.printf("[WiFi] Connected. IP address: %s\n", WiFi.localIP().toString().c_str());
}

// Blocks (bounded) until the device clock reflects real UTC time via NTP.
// A device timestamp is only trustworthy once this succeeds - see the
// Tier 5 hardware audit's clock/timestamp-semantics discussion.
void syncTime() {
    Serial.println("[Time] Requesting NTP sync (UTC)...");
    configTime(0, 0, "pool.ntp.org", "time.nist.gov");

    unsigned long start = millis();
    time_t now = time(nullptr);
    while (now < MIN_PLAUSIBLE_EPOCH) {
        if (millis() - start > TIME_SYNC_TIMEOUT_MILLIS) {
            Serial.println("[Time] NTP sync timed out; timestamps may be inaccurate until it succeeds later.");
            return;
        }
        delay(250);
        now = time(nullptr);
    }
    Serial.println("[Time] NTP sync complete.");
}

bool isTimeSynced() {
    return time(nullptr) >= MIN_PLAUSIBLE_EPOCH;
}

// Formats the current UTC time as an ISO-8601 string, e.g.
// "2026-01-01T00:00:00Z" - directly parseable by the backend's
// Instant-typed TelemetryRequest.timestamp field.
String currentIsoTimestampUtc() {
    time_t now = time(nullptr);
    struct tm timeinfo;
    gmtime_r(&now, &timeinfo);
    char buffer[25];
    strftime(buffer, sizeof(buffer), "%Y-%m-%dT%H:%M:%SZ", &timeinfo);
    return String(buffer);
}

void connectMqtt() {
    if (mqttClient.connected()) {
        return;
    }

    Serial.printf("[MQTT] Connecting to %s:%d as client '%s'...\n",
                  MQTT_BROKER_HOST, MQTT_BROKER_PORT, mqttClientId.c_str());

    unsigned long start = millis();
    while (!mqttClient.connect(mqttClientId.c_str())) {
        if (millis() - start > MQTT_CONNECT_TIMEOUT_MILLIS) {
            Serial.println("[MQTT] Connection attempt timed out; will retry.");
            return;
        }
        delay(250);
        Serial.print(".");
    }
    Serial.println();
    Serial.println("[MQTT] Connected.");

    // Tier 5B: publish the retained "online" birth message immediately
    // after every successful (re)connect - including a reconnect using
    // this same stable client ID, which is what makes the ordering
    // guarantee against the LWT "offline" (registered once, before the
    // very first connect, in setup()) hold: the broker evicts and fires
    // the will for any prior connection under this same client ID before
    // this new connection can even reach this point.
    bool published = mqttClient.publish(deviceStatusTopic, "online", /*retained=*/true, /*qos=*/1);
    if (published) {
        Serial.printf("[MQTT] Published retained online status to %s\n", deviceStatusTopic.c_str());
    } else {
        Serial.printf("[MQTT] Failed to publish online status to %s\n", deviceStatusTopic.c_str());
    }
}

// Reads the sensor and publishes one telemetry message in the exact NEXUS
// wire contract. If the sensor read fails (NaN), nothing is published this
// cycle - an invalid/garbage value must never reach NEXUS.
void readAndPublishTelemetry() {
    float temperatureCelsius = dht.readTemperature();

    if (isnan(temperatureCelsius)) {
        Serial.println("[Sensor] DHT read failed (NaN); skipping this publish cycle.");
        return;
    }

    if (!isTimeSynced()) {
        Serial.println("[Time] Clock not yet synced; skipping this publish cycle rather than publishing an untrustworthy timestamp.");
        return;
    }

    JsonDocument doc;
    doc["deviceId"] = NEXUS_DEVICE_UUID;
    doc["timestamp"] = currentIsoTimestampUtc();
    doc["sensorType"] = "TEMPERATURE_SENSOR"; // must match the Python simulator's exact value for wire-level parity
    doc["value"] = temperatureCelsius;
    doc["unit"] = "CELSIUS";

    String payload;
    serializeJson(doc, payload);

    // QoS 1, matching the existing NEXUS telemetry contract exactly.
    bool ok = mqttClient.publish(telemetryTopic, payload, /*retained=*/false, /*qos=*/1);

    if (ok) {
        Serial.printf("[MQTT] Published to %s: %s\n", telemetryTopic.c_str(), payload.c_str());
    } else {
        Serial.printf("[MQTT] Publish FAILED to %s\n", telemetryTopic.c_str());
    }
}

void setup() {
    Serial.begin(115200);
    delay(500);
    Serial.println();
    Serial.println("[Boot] NEXUS Tier 5A ESP32 temperature sensor firmware starting.");

    dht.begin();

    telemetryTopic = String("nexus/devices/") + NEXUS_DEVICE_UUID + "/telemetry";
    deviceStatusTopic = String("nexus/devices/") + NEXUS_DEVICE_UUID + "/status";
    // Client ID derived from the device UUID (not a fixed shared string) so
    // multiple real devices never collide, unlike the simulator's current
    // single hardcoded MQTT client ID. This same, stable client ID across
    // every reconnect is also what makes the LWT/birth-message ordering
    // guarantee hold (see connectMqtt()).
    mqttClientId = String("nexus-esp32-") + String(NEXUS_DEVICE_UUID).substring(0, 8);

    Serial.printf("[Boot] Device UUID: %s\n", NEXUS_DEVICE_UUID);
    Serial.printf("[Boot] Telemetry topic: %s\n", telemetryTopic.c_str());
    Serial.printf("[Boot] Device status topic: %s\n", deviceStatusTopic.c_str());

    connectWiFi();
    syncTime();

    mqttClient.begin(MQTT_BROKER_HOST, MQTT_BROKER_PORT, netClient);
    // Tier 5B: the will must be registered before the first connect() call -
    // it is stored by the client and applied to every subsequent connect
    // attempt (reconnects included), so this is a one-time setup call, not
    // something repeated per reconnect.
    mqttClient.setWill(deviceStatusTopic.c_str(), "offline", /*retained=*/true, /*qos=*/1);
    connectMqtt();
}

void loop() {
    connectWiFi();
    connectMqtt();
    mqttClient.loop();

    unsigned long now = millis();
    if (now - lastPublishMillis >= PUBLISH_INTERVAL_MILLIS) {
        lastPublishMillis = now;
        if (WiFi.status() == WL_CONNECTED && mqttClient.connected()) {
            readAndPublishTelemetry();
        } else {
            Serial.println("[Loop] Skipping publish cycle: Wi-Fi or MQTT not connected.");
        }
    }
}
