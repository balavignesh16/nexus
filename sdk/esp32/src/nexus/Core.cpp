#include "Core.h"

#include <string.h>

namespace nexus {

const char* statusName(Status status) {
    switch (status) {
        case OK: return "OK";
        case CLOCK_NOT_SYNCED: return "CLOCK_NOT_SYNCED";
        case BUFFER_TOO_SMALL: return "BUFFER_TOO_SMALL";
        case INVALID_DEVICE_ID: return "INVALID_DEVICE_ID";
        case INVALID_MESSAGE_ID: return "INVALID_MESSAGE_ID";
        case INVALID_COMMAND_ID: return "INVALID_COMMAND_ID";
        case INVALID_FIELD: return "INVALID_FIELD";
        case NON_FINITE_VALUE: return "NON_FINITE_VALUE";
        case COUNTER_EXHAUSTED: return "COUNTER_EXHAUSTED";
    }
    return "UNKNOWN";
}

static bool isHex(char c) { return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'); }

bool isUuidText(const char* text, size_t length) {
    if (text == 0 || length != 36) return false;
    for (size_t i = 0; i < 36; ++i) {
        if (i == 8 || i == 13 || i == 18 || i == 23) {
            if (text[i] != '-') return false;
        } else if (!isHex(text[i])) {
            return false;
        }
    }
    return true;
}

static const char kHex[] = "0123456789abcdef";

void MessageIds::format(uint64_t bootId, uint32_t counter, char* out) {
    // 16 bytes: counter (big endian, 4) | boot id (big endian, 8) | zero (4); then the UUID version 4 and variant bits.
    uint8_t b[16];
    for (int i = 0; i < 4; ++i) b[i] = (uint8_t)(counter >> (24 - 8 * i));
    for (int i = 0; i < 8; ++i) b[4 + i] = (uint8_t)(bootId >> (56 - 8 * i));
    for (int i = 12; i < 16; ++i) b[i] = 0;
    b[6] = (uint8_t)((b[6] & 0x0F) | 0x40);
    b[8] = (uint8_t)((b[8] & 0x3F) | 0x80);
    size_t o = 0;
    for (int i = 0; i < 16; ++i) {
        if (i == 4 || i == 6 || i == 8 || i == 10) out[o++] = '-';
        out[o++] = kHex[b[i] >> 4];
        out[o++] = kHex[b[i] & 0x0F];
    }
    out[o] = 0;
}

Status MessageIds::next(char* out, size_t capacity) {
    if (out == 0 || capacity < 37) return BUFFER_TOO_SMALL;
    if (counter_ == 0xFFFFFFFFu) return COUNTER_EXHAUSTED;
    ++counter_;
    format(bootId_, counter_, out);
    return OK;
}

Status MessageIds::idFor(uint32_t counter, char* out, size_t capacity) const {
    if (out == 0 || capacity < 37) return BUFFER_TOO_SMALL;
    if (counter == 0 || counter > counter_) return INVALID_FIELD;
    format(bootId_, counter, out);
    return OK;
}

static Status topic(char* out, size_t capacity, const char* deviceId, const char* suffix) {
    if (out == 0) return BUFFER_TOO_SMALL;
    if (capacity > 0) out[0] = 0;
    if (deviceId == 0 || !isUuidText(deviceId, strlen(deviceId))) return INVALID_DEVICE_ID;
    const char* prefix = "nexus/devices/";
    size_t need = strlen(prefix) + 36 + strlen(suffix) + 1;
    if (capacity < need) return BUFFER_TOO_SMALL;
    size_t o = 0;
    memcpy(out + o, prefix, strlen(prefix)); o += strlen(prefix);
    memcpy(out + o, deviceId, 36); o += 36;
    memcpy(out + o, suffix, strlen(suffix)); o += strlen(suffix);
    out[o] = 0;
    return OK;
}

Status topicTelemetry(char* out, size_t capacity, const char* deviceId) { return topic(out, capacity, deviceId, "/telemetry"); }
Status topicStatus(char* out, size_t capacity, const char* deviceId) { return topic(out, capacity, deviceId, "/status"); }
Status topicCommand(char* out, size_t capacity, const char* deviceId) { return topic(out, capacity, deviceId, "/commands"); }
Status topicAck(char* out, size_t capacity, const char* deviceId) { return topic(out, capacity, deviceId, "/commands/ack"); }

Status formatIsoTimestamp(int64_t epoch, char* out, size_t capacity) {
    if (out == 0 || capacity < 21) return BUFFER_TOO_SMALL;
    if (epoch < 0 || epoch > 253402300799LL) { out[0] = 0; return INVALID_FIELD; }
    int64_t days = epoch / 86400;
    int64_t rest = epoch % 86400;
    // civil-from-days (proleptic Gregorian), after H. Hinnant
    int64_t z = days + 719468;
    int64_t era = z / 146097;
    int64_t doe = z - era * 146097;
    int64_t yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365;
    int64_t y = yoe + era * 400;
    int64_t doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    int64_t mp = (5 * doy + 2) / 153;
    int64_t d = doy - (153 * mp + 2) / 5 + 1;
    int64_t m = mp < 10 ? mp + 3 : mp - 9;
    if (m <= 2) y += 1;
    int64_t hh = rest / 3600, mm = (rest % 3600) / 60, ss = rest % 60;
    int64_t parts[6] = {y, m, d, hh, mm, ss};
    const int widths[6] = {4, 2, 2, 2, 2, 2};
    const char seps[6] = {'-', '-', 'T', ':', ':', 'Z'};
    size_t o = 0;
    for (int i = 0; i < 6; ++i) {
        int64_t v = parts[i];
        for (int w = widths[i] - 1; w >= 0; --w) {
            int64_t p = 1;
            for (int k = 0; k < w; ++k) p *= 10;
            out[o++] = (char)('0' + (v / p) % 10);
        }
        out[o++] = seps[i];
    }
    out[o] = 0;
    return OK;
}

}  // namespace nexus
