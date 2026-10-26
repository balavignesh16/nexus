// TemperatureNode: publishes a temperature reading to NEXUS every 10 seconds using the NEXUS device SDK.
//
// What it shows: contract 1.0 telemetry with a stable messageId, the retained online status, and the refusal to publish before the
// clock is synchronised. Replace readTemperatureCelsius() with your sensor. Provision the device in NEXUS first and paste its UUID.
// Host-compiled against stubs only; not flashed or run on hardware in this repository.
#include <WiFi.h>
#include <MQTT.h>
#include <time.h>

#include <NexusArduino.h>

static const char* WIFI_SSID = "CHANGE_ME";
static const char* WIFI_PASSWORD = "CHANGE_ME";
static const char* BROKER_HOST = "192.168.0.10";
static const int BROKER_PORT = 1883;
static const char* DEVICE_ID = "3f2c8a54-9d1e-4b7a-8c61-0e5d2a7f9b13";  // the UUID NEXUS knows this device by
static const int LED_PIN = 2;
static const unsigned long PUBLISH_INTERVAL_MILLIS = 10000UL;

WiFiClient network;
MQTTClient mqtt(512);
nexus::ArduinoHal<MQTTClient> hal(mqtt, LED_PIN);
nexus::DeviceNode* node = nullptr;
unsigned long lastPublish = 0;

static float readTemperatureCelsius() { return 24.5f; }  // replace with a real sensor read

void setup() {
    Serial.begin(115200);
    pinMode(LED_PIN, OUTPUT);
    WiFi.begin(WIFI_SSID, WIFI_PASSWORD);
    configTime(0, 0, "pool.ntp.org");  // the SDK refuses to publish until this has set a plausible UTC time

    static nexus::DeviceNode device(hal, DEVICE_ID, nexus::bootIdFrom(0x9E3779B9u, 0x7F4A7C15u /* use esp_random() on the device */));
    node = &device;

    mqtt.begin(BROKER_HOST, BROKER_PORT, network);
    mqtt.connect("nexus-temperature-node");
    node->publishStatus(true);
}

void loop() {
    mqtt.loop();
    unsigned long now = millis();
    if (now - lastPublish < PUBLISH_INTERVAL_MILLIS && lastPublish != 0) return;
    lastPublish = now == 0 ? 1 : now;

    nexus::Status status = node->publishNumber("TEMPERATURE_SENSOR", readTemperatureCelsius(), "CELSIUS");
    if (status == nexus::CLOCK_NOT_SYNCED) {
        Serial.println("[Time] Clock not synced yet; skipping this publish cycle.");
    } else if (status != nexus::OK) {
        Serial.printf("[Nexus] Telemetry refused: %s\n", nexus::statusName(status));
    }
}
