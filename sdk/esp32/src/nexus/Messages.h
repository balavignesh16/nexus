// Telemetry, acknowledgement and command messages of the NEXUS wire contract (see Core.h for the scope and limits).
#ifndef NEXUS_MESSAGES_H
#define NEXUS_MESSAGES_H

#include "Core.h"

namespace nexus {

// ---- telemetry

struct Telemetry {
    enum Kind { NUMBER, INTEGER, BOOLEAN };

    const char* deviceId;           // canonical UUID text, must equal the device id in the topic
    const char* sensorType;         // for example TEMPERATURE_SENSOR
    const char* unit;               // for example CELSIUS
    Kind kind;
    double number;
    int64_t integer;
    bool boolean;
    const char* messageId;          // UUID text from MessageIds; NULL builds the legacy form (no contractVersion, no messageId)
    const char* capabilityKey;      // optional, NULL = absent
    const char* readbackCommandId;  // optional UUID text, NULL = absent

    static Telemetry ofNumber(const char* deviceId, const char* sensorType, double value, const char* unit, const char* messageId);
    static Telemetry ofInteger(const char* deviceId, const char* sensorType, int64_t value, const char* unit, const char* messageId);
    static Telemetry ofBoolean(const char* deviceId, const char* sensorType, bool value, const char* unit, const char* messageId);
};

// Builds one telemetry document as compact JSON. contractVersion 1.0 with messageId when t.messageId is set; the legacy form of the
// committed firmware (deviceId, timestamp, sensorType, value, unit) when it is NULL. Refuses with CLOCK_NOT_SYNCED before the clock
// is trustworthy. On success *length is the document length and out is NUL-terminated. Nothing is written on failure except out[0] = 0.
Status buildTelemetry(char* out, size_t capacity, size_t* length, int64_t nowEpochSeconds, const Telemetry& t);

// ---- acknowledgement

// status: "ACCEPTED" or "REJECTED". reason: NULL for ACCEPTED; for REJECTED one of "MALFORMED", "UNSUPPORTED", "STALE", "DEVICE_REJECTED".
// withContractVersion false builds the legacy form of the committed firmware byte for byte.
Status buildAck(char* out, size_t capacity, size_t* length, int64_t nowEpochSeconds, const char* commandId, const char* status,
                const char* reason, bool withContractVersion = true);

// ---- commands

// What a received command payload means. The first group produces NO acknowledgement (there is nothing to correlate it with,
// exactly as in the committed firmware); the second group is a REJECTED acknowledgement; ACCEPTED is applied and acknowledged.
enum Disposition {
    NO_ACK_CLOCK_NOT_SYNCED = 0,  // checked first, before the payload is even looked at (firmware order)
    NO_ACK_NOT_JSON,              // not one strict JSON object (this includes a duplicate member name, bad UTF-8, too deep or too large)
    NO_ACK_NO_COMMAND_ID,         // commandId missing, not a string, or not canonical UUID text
    REJECTED_MALFORMED,           // a required field is missing or has the wrong type (value must be a boolean, timestamp a 32-bit integer)
    REJECTED_UNSUPPORTED,         // only capability LED with command SET_STATE exists in the esp32-legacy-v1 profile
    REJECTED_STALE,               // |now - timestamp| > 30 s
    ACCEPTED
};

const char* dispositionName(Disposition d);
inline bool dispositionAcknowledges(Disposition d) { return d >= REJECTED_MALFORMED; }
// "ACCEPTED" / "REJECTED" for a disposition that acknowledges, NULL otherwise.
const char* dispositionAckStatus(Disposition d);
// "MALFORMED" / "UNSUPPORTED" / "STALE" for a rejection, NULL otherwise.
const char* dispositionAckReason(Disposition d);

struct CommandResult {
    Disposition disposition;
    char commandId[37];  // valid (NUL-terminated UUID text) when the disposition acknowledges
    bool value;          // the requested LED state, meaningful only when disposition == ACCEPTED
    int64_t timestamp;   // the command timestamp, meaningful when the payload got as far as a valid timestamp
};

// The largest command payload the parser examines. A larger payload is NO_ACK_NOT_JSON (a documented bound of this library; real
// NEXUS commands are about 200 bytes).
static const size_t kMaxCommandPayload = 512;

// The firmware's pipeline, in the firmware's order: clock synced, one strict JSON object, commandId, required fields, supported
// capability and command, freshness (absolute 30 s). The payload is never modified and is not required to be NUL-terminated.
CommandResult evaluateCommand(const uint8_t* payload, size_t length, int64_t nowEpochSeconds);

}  // namespace nexus

#endif
