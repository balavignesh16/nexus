// LedCommandNode: receives NEXUS LED commands, applies them, and acknowledges with the firmware's rules (MALFORMED / UNSUPPORTED /
// STALE, 30 second absolute freshness, no acknowledgement for a command without a usable commandId).
//
// The acknowledgement says only that this device accepted the command and set the pin. It is not proof of the physical result.
// Host-compiled against stubs only; not flashed or run on hardware in this repository.
#include <WiFi.h>
#include <MQTT.h>
#include <time.h>

#include <NexusArduino.h>

static const char* WIFI_SSID = "CHANGE_ME";
static const char* WIFI_PASSWORD = "CHANGE_ME";
static const char* BROKER_HOST = "192.168.0.10";
static const int BROKER_PORT = 1883;
static const char* DEVICE_ID = "3f2c8a54-9d1e-4b7a-8c61-0e5d2a7f9b13";
static const int LED_PIN = 2;

WiFiClient network;
MQTTClient mqtt(512);
nexus::ArduinoHal<MQTTClient> hal(mqtt, LED_PIN);
nexus::DeviceNode* node = nullptr;
char commandTopic[96];

// 256dpi/MQTT hands over the raw bytes and their length, so no String copy is needed.
static void onMessage(MQTTClient* client, char topic[], char bytes[], int length) {
    (void)client;
    if (strcmp(topic, commandTopic) != 0 || length < 0) return;
    node->onCommand((const uint8_t*)bytes, (size_t)length);
}

void setup() {
    Serial.begin(115200);
    pinMode(LED_PIN, OUTPUT);
    WiFi.begin(WIFI_SSID, WIFI_PASSWORD);
    configTime(0, 0, "pool.ntp.org");

    static nexus::DeviceNode device(hal, DEVICE_ID, nexus::bootIdFrom(0x1B873593u, 0xCC9E2D51u /* use esp_random() on the device */));
    node = &device;
    nexus::topicCommand(commandTopic, sizeof(commandTopic), DEVICE_ID);

    mqtt.begin(BROKER_HOST, BROKER_PORT, network);
    mqtt.onMessageAdvanced(onMessage);
    mqtt.connect("nexus-led-node");
    mqtt.subscribe(commandTopic, 1);
    node->publishStatus(true);
}

void loop() {
    mqtt.loop();
}
