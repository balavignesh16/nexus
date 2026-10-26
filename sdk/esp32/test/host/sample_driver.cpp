// Runs a sample sketch on the host against the stubs: setup(), then loop() under a scripted clock. Exit status 0 means the sketch
// behaved as documented. This proves the sketch compiles and its logic runs against stubs; it is not a hardware test.
#include <stdio.h>
#include <string.h>

#include <string>

#include "Arduino.h"
#include "MQTT.h"

void setup();
void loop();
extern MQTTClient mqtt;

static int failures = 0;

#define CHECK(cond)                                                                    \
    do {                                                                               \
        if (!(cond)) {                                                                 \
            fprintf(stderr, "CHECK FAILED %s:%d: %s\n", __FILE__, __LINE__, #cond);    \
            ++failures;                                                                \
        }                                                                              \
    } while (0)

static const char* kDevice = "3f2c8a54-9d1e-4b7a-8c61-0e5d2a7f9b13";
static const char* kCommand = "a1b2c3d4-e5f6-4789-8abc-def012345678";
static const int64_t kNow = 1767225600;

static std::string command(const char* capability, bool value, int64_t timestamp) {
    char buffer[300];
    snprintf(buffer, sizeof(buffer),
             "{\"commandId\":\"%s\",\"capability\":\"%s\",\"command\":\"SET_STATE\",\"value\":%s,\"timestamp\":%ld}", kCommand,
             capability, value ? "true" : "false", (long)timestamp);
    return buffer;
}

static int runTemperature() {
    stubWorld().epoch = 0;  // the clock is not synchronised yet
    setup();
    CHECK(mqtt.published.size() == 1);  // only the retained online status
    CHECK(mqtt.published[0].payload == "online" && mqtt.published[0].retained && mqtt.published[0].qos == 1);
    CHECK(mqtt.published[0].topic == std::string("nexus/devices/") + kDevice + "/status");
    stubWorld().millis = 5;
    loop();
    CHECK(mqtt.published.size() == 1);  // refused: no telemetry before the clock is synced

    stubWorld().epoch = kNow;
    stubWorld().millis = 20000;
    loop();
    CHECK(mqtt.published.size() == 2);
    if (mqtt.published.size() == 2) {
        const StubPublished& t = mqtt.published[1];
        CHECK(t.topic == std::string("nexus/devices/") + kDevice + "/telemetry");
        CHECK(!t.retained && t.qos == 1);
        CHECK(t.payload.find("\"contractVersion\":\"1.0\"") == 0 + 1);
        CHECK(t.payload.find("\"value\":24.5") != std::string::npos);
        CHECK(t.payload.find("\"timestamp\":\"2026-01-01T00:00:00Z\"") != std::string::npos);
        CHECK(t.payload.find("\"messageId\":\"") != std::string::npos);
    }
    stubWorld().millis = 20100;
    loop();  // inside the publish interval: nothing new
    CHECK(mqtt.published.size() == 2);
    return failures;
}

static int runLed() {
    stubWorld().epoch = kNow;
    setup();
    CHECK(mqtt.subscriptions.size() == 1 && mqtt.subscriptions[0] == std::string("nexus/devices/") + kDevice + "/commands");
    CHECK(mqtt.published.size() == 1);

    mqtt.deliver((std::string("nexus/devices/") + kDevice + "/commands").c_str(), command("LED", true, kNow));
    CHECK(stubWorld().pins[2] == HIGH);
    CHECK(mqtt.published.size() == 2);
    if (mqtt.published.size() == 2) {
        CHECK(mqtt.published[1].topic == std::string("nexus/devices/") + kDevice + "/commands/ack");
        CHECK(mqtt.published[1].payload.find("\"status\":\"ACCEPTED\"") != std::string::npos);
    }

    mqtt.deliver((std::string("nexus/devices/") + kDevice + "/commands").c_str(), command("LED", false, kNow - 31));
    CHECK(stubWorld().pins[2] == HIGH);  // stale: not applied
    CHECK(mqtt.published.size() == 3);
    if (mqtt.published.size() == 3) CHECK(mqtt.published[2].payload.find("\"reason\":\"STALE\"") != std::string::npos);

    mqtt.deliver((std::string("nexus/devices/") + kDevice + "/commands").c_str(), command("FAN", true, kNow));
    CHECK(mqtt.published.size() == 4);
    if (mqtt.published.size() == 4) CHECK(mqtt.published[3].payload.find("\"reason\":\"UNSUPPORTED\"") != std::string::npos);

    mqtt.deliver((std::string("nexus/devices/") + kDevice + "/commands").c_str(), "not json");
    CHECK(mqtt.published.size() == 4);  // no commandId: no acknowledgement

    mqtt.deliver("nexus/devices/someone-else/commands", command("LED", false, kNow));
    CHECK(stubWorld().pins[2] == HIGH && mqtt.published.size() == 4);  // a foreign topic is ignored
    return failures;
}

int main(int argc, char** argv) {
    if (argc != 2) return 2;
    int result = strcmp(argv[1], "temperature") == 0 ? runTemperature() : strcmp(argv[1], "led") == 0 ? runLed() : 2;
    printf("%s sample: %s\n", argv[1], result == 0 ? "ok" : "FAILED");
    return result == 0 ? 0 : 1;
}
