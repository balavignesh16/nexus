// Host stub of the Arduino core, just enough for the sample sketches to compile and run on a PC. NOT the real Arduino API surface.
#ifndef STUB_ARDUINO_H
#define STUB_ARDUINO_H

#include <stdarg.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

#define HIGH 1
#define LOW 0
#define OUTPUT 1

// The scripted world a sketch runs in: the clock and the millisecond counter are set by the test, pins are recorded.
struct StubWorld {
    int64_t epoch;
    unsigned long millis;
    int pins[40];
};

inline StubWorld& stubWorld() {
    static StubWorld world = {0, 0, {0}};
    return world;
}

#define NEXUS_NOW_EPOCH() (stubWorld().epoch)

inline void pinMode(int, int) {}
inline void digitalWrite(int pin, int value) { stubWorld().pins[pin] = value; }
inline unsigned long millis() { return stubWorld().millis; }
inline void delay(unsigned long ms) { stubWorld().millis += ms; }
inline void configTime(long, long, const char*) {}

struct StubSerial {
    void begin(long) {}
    void println(const char* text) { fprintf(stderr, "[serial] %s\n", text); }
    void printf(const char* format, ...) __attribute__((format(printf, 2, 3))) {
        va_list args;
        va_start(args, format);
        fputs("[serial] ", stderr);
        vfprintf(stderr, format, args);
        va_end(args);
    }
};
static StubSerial Serial __attribute__((unused));

#endif
