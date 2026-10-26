// Host stub of the 256dpi/MQTT client (the library firmware/platformio.ini pins), limited to the calls the samples make. It records what
// is published and lets a test deliver a message to the registered callback. NOT the real library.
#ifndef STUB_MQTT_H
#define STUB_MQTT_H

#include <string>
#include <vector>

#include "WiFi.h"

class MQTTClient;
typedef void (*MQTTClientCallbackAdvanced)(MQTTClient* client, char topic[], char bytes[], int length);

struct StubPublished {
    std::string topic;
    std::string payload;
    bool retained;
    int qos;
};

class MQTTClient {
public:
    explicit MQTTClient(int = 128) : callback_(0), connected_(false) {}
    void begin(const char*, int, WiFiClient&) {}
    void onMessageAdvanced(MQTTClientCallbackAdvanced callback) { callback_ = callback; }
    bool connect(const char*) { connected_ = true; return true; }
    bool subscribe(const char* topic, int) { subscriptions.push_back(topic); return true; }
    bool publish(const char* topic, const char* payload, int length, bool retained, int qos) {
        StubPublished p = {topic, std::string(payload, (size_t)length), retained, qos};
        published.push_back(p);
        return true;
    }
    bool loop() { return connected_; }
    bool connected() const { return connected_; }

    // test side
    void deliver(const char* topic, const std::string& bytes) {
        std::string t(topic), b(bytes);
        if (callback_) callback_(this, &t[0], &b[0], (int)b.size());
    }
    std::vector<StubPublished> published;
    std::vector<std::string> subscriptions;

private:
    MQTTClientCallbackAdvanced callback_;
    bool connected_;
};

#endif
