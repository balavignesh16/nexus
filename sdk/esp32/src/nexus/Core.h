// NEXUS device SDK for ESP32 (host-testable core). Plain C++11, no heap, no Arduino or ESP-IDF include: the same code
// builds on the host (g++, PlatformIO "native") and for the ESP32 (Arduino framework).
//
// This library is NOT firmware and imports nothing from firmware/. It implements the NEXUS wire contract 1.0
// (contracts/v1, architecture/phase5/adapter-contract.md) for the one device profile that exists today, esp32-legacy-v1.
// It has been tested on the host only; nothing here claims validation on physical hardware.
#ifndef NEXUS_CORE_H
#define NEXUS_CORE_H

#include <stddef.h>
#include <stdint.h>

namespace nexus {

// Why an operation produced nothing. OK is the only success value; every other value means no message was produced.
enum Status {
    OK = 0,
    CLOCK_NOT_SYNCED,    // the device clock is not trustworthy yet: no timestamped message is produced (as the firmware does)
    BUFFER_TOO_SMALL,
    INVALID_DEVICE_ID,   // not canonical 8-4-4-4-12 hexadecimal UUID text
    INVALID_MESSAGE_ID,
    INVALID_COMMAND_ID,
    INVALID_FIELD,       // empty, too long, non-ASCII or otherwise unusable text, or an unknown ack status or reason
    NON_FINITE_VALUE,    // NaN or infinity can never be sent
    COUNTER_EXHAUSTED    // the 32-bit message counter of this boot is used up; reboot (a new boot id) before sending more
};

const char* statusName(Status status);

// ---- clock and freshness (contract rule: the firmware's, decision D4)

// An NTP-synchronised epoch is well past this value (2024-01-01T00:00:00Z); anything below means time() was never set.
static const int64_t kMinPlausibleEpoch = 1704067200LL;
// The command freshness window of the esp32-legacy-v1 profile. Absolute: a command up to 30 s in the future is fresh too.
static const int64_t kCommandFreshnessWindowSeconds = 30;

inline bool clockIsSynced(int64_t nowEpochSeconds) { return nowEpochSeconds >= kMinPlausibleEpoch; }

// STALE when |now - commandTimestamp| > window. now is the whole-second device clock.
inline bool isStale(int64_t nowEpochSeconds, int64_t commandTimestamp, int64_t windowSeconds = kCommandFreshnessWindowSeconds) {
    int64_t age = nowEpochSeconds - commandTimestamp;
    if (age < 0) age = -age;
    return age > windowSeconds;
}

// ---- identifiers

bool isUuidText(const char* text, size_t length);

// Stable message ids. A MessageIds object belongs to one boot: the caller supplies a random 64-bit boot id once per boot
// (esp_random() twice, for example) and the object counts samples. The id of a sample is a pure function of (boot id, counter),
// so retrying the same sample (QoS 1 redelivery, an application retry) must reuse the id it already has: call next() once per
// NEW sample and keep the text, or ask idFor(counter) again. A reboot changes the boot id, so ids never repeat across boots
// (58 of the 64 bits are used; six are fixed UUID version and variant bits).
class MessageIds {
public:
    explicit MessageIds(uint64_t bootId) : bootId_(bootId), counter_(0) {}
    // For a device that persists its counter across a warm restart within the same boot id, and for tests of the exhaustion bound.
    MessageIds(uint64_t bootId, uint32_t alreadyIssued) : bootId_(bootId), counter_(alreadyIssued) {}
    // Writes the id of the next new sample into out (at least 37 bytes). COUNTER_EXHAUSTED after 2^32 - 1 samples in one boot.
    Status next(char* out, size_t capacity);
    // Writes the id of an earlier sample (1 .. samples issued) again, for a retry. INVALID_FIELD when the counter was never issued.
    Status idFor(uint32_t counter, char* out, size_t capacity) const;
    uint32_t issued() const { return counter_; }
    uint64_t bootId() const { return bootId_; }
    static void format(uint64_t bootId, uint32_t counter, char* out37);

private:
    uint64_t bootId_;
    uint32_t counter_;
};

// ---- topics (adapter-contract.md section 7). capacity >= 64 + 1 is always enough for a canonical UUID.

Status topicTelemetry(char* out, size_t capacity, const char* deviceId);
Status topicStatus(char* out, size_t capacity, const char* deviceId);
Status topicCommand(char* out, size_t capacity, const char* deviceId);
Status topicAck(char* out, size_t capacity, const char* deviceId);

// status payloads are bare text (no JSON): "online" or "offline", published retained.
inline const char* statusPayload(bool online) { return online ? "online" : "offline"; }

// ---- time formatting

// "2026-01-01T00:00:00Z" (seconds precision, the form the committed firmware sends). 21 bytes with the terminator.
Status formatIsoTimestamp(int64_t epochSeconds, char* out, size_t capacity);

}  // namespace nexus

#endif
