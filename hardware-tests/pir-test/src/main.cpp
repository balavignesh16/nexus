#include <Arduino.h>

static const int PIR_PIN = 27;

static bool lastState = false;

void setup() {
    Serial.begin(115200);
    delay(500);

    pinMode(PIR_PIN, INPUT);

    Serial.println();
    Serial.println("================================");
    Serial.println("NEXUS ESP32 #2 - PIR TEST");
    Serial.println("HC-SR501 on GPIO27");
    Serial.println("================================");
    Serial.println("PIR warming up...");
    Serial.println("Do not move in front of the sensor.");
    Serial.println("Waiting 60 seconds...");

    unsigned long start = millis();

    while (millis() - start < 60000UL) {
        unsigned long elapsed = (millis() - start) / 1000;
        Serial.printf("Warmup: %lus / 60s\n", elapsed);
        delay(1000);
    }

    lastState = digitalRead(PIR_PIN) == HIGH;

    Serial.println();
    Serial.println("PIR READY.");
    Serial.println("Move your hand/body in front of the sensor.");
    Serial.println();
}

void loop() {
    bool currentState = digitalRead(PIR_PIN) == HIGH;

    if (currentState != lastState) {
        lastState = currentState;

        if (currentState) {
            Serial.println("[PIR] MOTION DETECTED");
        } else {
            Serial.println("[PIR] MOTION ENDED");
        }
    }

    delay(50);
}