#pragma once
#include <stdint.h>

namespace usbv1 {
struct Living {
    int mode = 0; // AUTO, FORCE ON, FORCE OFF
    bool raw = false, stable = false, seenMotion = false;
    uint32_t changed = 0, motionAt = 0;
    bool step(uint32_t now, bool motion, bool pressed) {
        if (pressed != raw) { raw = pressed; changed = now; }
        if (raw != stable && uint32_t(now - changed) >= 40) {
            stable = raw;
            if (stable) mode = (mode + 1) % 3;
        }
        if (motion) { seenMotion = true; motionAt = now; }
        return mode == 1 || (mode == 0 && seenMotion && uint32_t(now - motionAt) < 3000);
    }
};
inline int severity(int previous, int delta) {
    if (delta >= 600) return 2;
    if (previous == 2 && delta >= 500) return 2;
    if (delta >= 300) return 1;
    if (previous >= 1 && delta >= 200) return 1;
    return 0;
}
inline bool elapsed(uint32_t now, uint32_t since, uint32_t duration) { return uint32_t(now - since) >= duration; }
}
