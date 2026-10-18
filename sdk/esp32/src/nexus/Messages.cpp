#include "Messages.h"

#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

namespace nexus {

namespace {

// Appends to a caller buffer and remembers an overflow; never writes past capacity - 1 and always leaves a terminator.
class Writer {
public:
    Writer(char* buffer, size_t capacity) : buffer_(buffer), capacity_(capacity), length_(0), overflow_(false) {
        if (capacity_ > 0) buffer_[0] = 0;
    }
    void raw(const char* text) { raw(text, strlen(text)); }
    void raw(const char* text, size_t n) {
        if (overflow_) return;
        if (length_ + n + 1 > capacity_) { overflow_ = true; return; }
        memcpy(buffer_ + length_, text, n);
        length_ += n;
        buffer_[length_] = 0;
    }
    void quoted(const char* text) {  // text was validated: printable ASCII, so only the quote and the backslash need escaping
        raw("\"");
        for (const char* p = text; *p; ++p) {
            if (*p == '"' || *p == '\\') raw("\\", 1);
            raw(p, 1);
        }
        raw("\"");
    }
    bool overflowed() const { return overflow_; }
    size_t length() const { return length_; }
    void reset() { length_ = 0; if (capacity_ > 0) buffer_[0] = 0; }

private:
    char* buffer_;
    size_t capacity_;
    size_t length_;
    bool overflow_;
};

bool printableAscii(const char* text, size_t maxLength) {
    if (text == 0 || text[0] == 0) return false;
    size_t n = strlen(text);
    if (n > maxLength) return false;
    bool visible = false;  // the contract wants non-blank text
    for (size_t i = 0; i < n; ++i) {
        unsigned char c = (unsigned char)text[i];
        if (c < 0x20 || c > 0x7E) return false;
        if (c != ' ') visible = true;
    }
    return visible;
}

// Shortest decimal text that parses back to the same double (at most 17 significant digits), always a valid JSON number.
void formatDouble(double value, char* out, size_t capacity) {
    for (int precision = 1; precision <= 17; ++precision) {
        snprintf(out, capacity, "%.*g", precision, value);
        if (strtod(out, 0) == value) break;
    }
    for (char* p = out; *p; ++p) {
        if (*p == ',') *p = '.';  // a locale with a decimal comma must never leak into JSON
    }
    if (strpbrk(out, ".eE") == 0) strncat(out, ".0", capacity - strlen(out) - 1);  // 25 -> 25.0, like the Python SDK
}

void formatInt64(int64_t v, char* out, size_t capacity) {
    char tmp[24];
    size_t n = 0;
    bool negative = v < 0;
    uint64_t u = negative ? (uint64_t)0 - (uint64_t)v : (uint64_t)v;
    do { tmp[n++] = (char)('0' + u % 10); u /= 10; } while (u != 0 && n < sizeof(tmp));
    size_t o = 0;
    if (negative && o + 1 < capacity) out[o++] = '-';
    while (n > 0 && o + 1 < capacity) out[o++] = tmp[--n];
    out[o] = 0;
}

Status finish(Writer& w, char* out, size_t* length) {
    if (w.overflowed()) {
        if (out) out[0] = 0;
        return BUFFER_TOO_SMALL;
    }
    if (length) *length = w.length();
    return OK;
}

}  // namespace

Telemetry Telemetry::ofNumber(const char* deviceId, const char* sensorType, double value, const char* unit, const char* messageId) {
    Telemetry t = {deviceId, sensorType, unit, NUMBER, value, 0, false, messageId, 0, 0};
    return t;
}

Telemetry Telemetry::ofInteger(const char* deviceId, const char* sensorType, int64_t value, const char* unit, const char* messageId) {
    Telemetry t = {deviceId, sensorType, unit, INTEGER, 0.0, value, false, messageId, 0, 0};
    return t;
}

Telemetry Telemetry::ofBoolean(const char* deviceId, const char* sensorType, bool value, const char* unit, const char* messageId) {
    Telemetry t = {deviceId, sensorType, unit, BOOLEAN, 0.0, 0, value, messageId, 0, 0};
    return t;
}

Status buildTelemetry(char* out, size_t capacity, size_t* length, int64_t now, const Telemetry& t) {
    if (out == 0 || capacity == 0) return BUFFER_TOO_SMALL;
    out[0] = 0;
    if (length) *length = 0;
    // Order of refusal: the clock first (the firmware skips the cycle before looking at anything else), then the arguments.
    if (!clockIsSynced(now)) return CLOCK_NOT_SYNCED;
    if (t.deviceId == 0 || !isUuidText(t.deviceId, strlen(t.deviceId))) return INVALID_DEVICE_ID;
    if (t.messageId != 0 && !isUuidText(t.messageId, strlen(t.messageId))) return INVALID_MESSAGE_ID;
    if (t.readbackCommandId != 0 && !isUuidText(t.readbackCommandId, strlen(t.readbackCommandId))) return INVALID_COMMAND_ID;
    if (!printableAscii(t.sensorType, 64) || !printableAscii(t.unit, 64)) return INVALID_FIELD;
    if (t.capabilityKey != 0 && !printableAscii(t.capabilityKey, 64)) return INVALID_FIELD;
    if (t.kind == Telemetry::NUMBER && !isfinite(t.number)) return NON_FINITE_VALUE;
    // The legacy shape of the contract carries a numeric value only; a boolean value needs the generic shape, i.e. a capabilityKey.
    if (t.kind == Telemetry::BOOLEAN && t.capabilityKey == 0) return INVALID_FIELD;
    char timestamp[24];
    Status st = formatIsoTimestamp(now, timestamp, sizeof(timestamp));
    if (st != OK) return st;

    char number[40];
    switch (t.kind) {
        case Telemetry::NUMBER: formatDouble(t.number, number, sizeof(number)); break;
        case Telemetry::INTEGER: formatInt64(t.integer, number, sizeof(number)); break;
        case Telemetry::BOOLEAN: strcpy(number, t.boolean ? "true" : "false"); break;
    }

    Writer w(out, capacity);
    w.raw("{");
    if (t.messageId != 0) w.raw("\"contractVersion\":\"1.0\",");
    w.raw("\"deviceId\":"); w.quoted(t.deviceId);
    w.raw(",\"timestamp\":"); w.quoted(timestamp);
    w.raw(",\"sensorType\":"); w.quoted(t.sensorType);
    w.raw(",\"value\":"); w.raw(number);
    w.raw(",\"unit\":"); w.quoted(t.unit);
    if (t.messageId != 0) { w.raw(",\"messageId\":"); w.quoted(t.messageId); }
    if (t.capabilityKey != 0) { w.raw(",\"capabilityKey\":"); w.quoted(t.capabilityKey); }
    if (t.readbackCommandId != 0) { w.raw(",\"readbackCommandId\":"); w.quoted(t.readbackCommandId); }
    w.raw("}");
    return finish(w, out, length);
}

Status buildAck(char* out, size_t capacity, size_t* length, int64_t now, const char* commandId, const char* status, const char* reason,
                bool withContractVersion) {
    if (out == 0 || capacity == 0) return BUFFER_TOO_SMALL;
    out[0] = 0;
    if (length) *length = 0;
    if (!clockIsSynced(now)) return CLOCK_NOT_SYNCED;
    if (commandId == 0 || !isUuidText(commandId, strlen(commandId))) return INVALID_COMMAND_ID;
    if (status == 0) return INVALID_FIELD;
    bool accepted = strcmp(status, "ACCEPTED") == 0;
    bool rejected = strcmp(status, "REJECTED") == 0;
    if (!accepted && !rejected) return INVALID_FIELD;
    if (accepted && reason != 0) return INVALID_FIELD;
    if (rejected) {
        if (reason == 0) return INVALID_FIELD;
        if (strcmp(reason, "MALFORMED") != 0 && strcmp(reason, "UNSUPPORTED") != 0 && strcmp(reason, "STALE") != 0 &&
            strcmp(reason, "DEVICE_REJECTED") != 0) return INVALID_FIELD;
    }
    char timestamp[24];
    formatInt64(now, timestamp, sizeof(timestamp));

    Writer w(out, capacity);
    w.raw("{");
    if (withContractVersion) w.raw("\"contractVersion\":\"1.0\",");
    w.raw("\"commandId\":"); w.quoted(commandId);
    w.raw(",\"status\":"); w.quoted(status);
    w.raw(",\"timestamp\":"); w.raw(timestamp);
    if (reason != 0) { w.raw(",\"reason\":"); w.quoted(reason); }
    w.raw("}");
    return finish(w, out, length);
}

const char* dispositionName(Disposition d) {
    switch (d) {
        case NO_ACK_CLOCK_NOT_SYNCED: return "NO_ACK:CLOCK_NOT_SYNCED";
        case NO_ACK_NOT_JSON: return "NO_ACK:NOT_JSON";
        case NO_ACK_NO_COMMAND_ID: return "NO_ACK:NO_COMMAND_ID";
        case REJECTED_MALFORMED: return "REJECTED:MALFORMED";
        case REJECTED_UNSUPPORTED: return "REJECTED:UNSUPPORTED";
        case REJECTED_STALE: return "REJECTED:STALE";
        case ACCEPTED: return "ACCEPTED";
    }
    return "UNKNOWN";
}

const char* dispositionAckStatus(Disposition d) {
    if (d == ACCEPTED) return "ACCEPTED";
    return d >= REJECTED_MALFORMED ? "REJECTED" : 0;
}

const char* dispositionAckReason(Disposition d) {
    switch (d) {
        case REJECTED_MALFORMED: return "MALFORMED";
        case REJECTED_UNSUPPORTED: return "UNSUPPORTED";
        case REJECTED_STALE: return "STALE";
        default: return 0;
    }
}

}  // namespace nexus
