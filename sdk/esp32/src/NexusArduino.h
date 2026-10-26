// Glue between the portable library and the Arduino-ESP32 framework. Header only and templated on the MQTT client type, so the library
// itself depends on neither the Arduino framework nor an MQTT library. Written against the public API of the 256dpi/MQTT library
// (MQTTClient::publish(topic, payload, length, retained, qos)) that firmware/platformio.ini pins; it has not been compiled against that
// library or the ESP32 toolchain here (see README, "What is and is not verified").
#ifndef NEXUS_ARDUINO_H
#define NEXUS_ARDUINO_H

#include <time.h>

#include "NexusSdk.h"

#ifndef NEXUS_NOW_EPOCH
#define NEXUS_NOW_EPOCH() ((int64_t)time(nullptr))
#endif

namespace nexus {

template <class MqttClientT>
class ArduinoHal : public Hal {
public:
    ArduinoHal(MqttClientT& client, int ledPin) : client_(client), ledPin_(ledPin) {}

    int64_t nowEpochSeconds() override { return NEXUS_NOW_EPOCH(); }

    bool publish(const char* topic, const char* payload, size_t length, bool retained) override {
        return client_.publish(topic, payload, (int)length, retained, 1);  // QoS 1 everywhere, as the committed firmware
    }

    void setLed(bool on) override { digitalWrite(ledPin_, on ? HIGH : LOW); }

private:
    MqttClientT& client_;
    int ledPin_;
};

// 64 bits that differ from boot to boot, from the hardware random generator on an ESP32 (esp_random()).
inline uint64_t bootIdFrom(uint32_t high, uint32_t low) { return ((uint64_t)high << 32) | low; }

}  // namespace nexus

#endif
