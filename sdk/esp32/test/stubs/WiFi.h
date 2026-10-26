// Host stub of the ESP32 WiFi library: a connection that is always up. NOT the real API surface.
#ifndef STUB_WIFI_H
#define STUB_WIFI_H

#include "Arduino.h"

class WiFiClient {};

struct StubWiFi {
    void begin(const char*, const char*) {}
    bool connected() const { return true; }
};
static StubWiFi WiFi __attribute__((unused));

#endif
