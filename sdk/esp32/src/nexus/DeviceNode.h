// The reference device: a small, platform-independent implementation of one NEXUS device on top of the library. The samples use it
// on the ESP32 and the host conformance tests drive the very same class through a fake Hal, so what is tested on the host is what runs.
#ifndef NEXUS_DEVICENODE_H
#define NEXUS_DEVICENODE_H

#include "Messages.h"

namespace nexus {

// The three things the device needs from the platform. On the ESP32 the sample implements them with time(), the MQTT client and
// digitalWrite; in the host tests they are recorded and scripted.
class Hal {
public:
    virtual ~Hal() {}
    // Whole-second UTC epoch, or any value below kMinPlausibleEpoch while the clock has not been synchronised.
    virtual int64_t nowEpochSeconds() = 0;
    // Hands one message to the transport (MQTT QoS 1 for telemetry and acknowledgements, retained QoS 1 for status).
    virtual bool publish(const char* topic, const char* payload, size_t length, bool retained) = 0;
    // Applies a LED command that passed every check.
    virtual void setLed(bool on) = 0;
};

class DeviceNode {
public:
    // bootId: 64 random bits chosen once per boot. deviceId: canonical UUID text provisioned for this device (copied).
    DeviceNode(Hal& hal, const char* deviceId, uint64_t bootId, bool contractVersionedAcks = true);

    Status begin() const { return begin_; }  // INVALID_DEVICE_ID when the id was unusable; nothing is published then

    // Publishes the retained status text.
    Status publishStatus(bool online);

    // Builds, validates the arguments of, remembers and publishes one NEW telemetry sample with a fresh message id. CLOCK_NOT_SYNCED
    // when the clock is not trustworthy (nothing is published and no id is consumed).
    Status publishNumber(const char* sensorType, double value, const char* unit);
    // A boolean value belongs to the generic shape of the contract, so a capabilityKey (the capability's key in NEXUS) is required.
    Status publishBoolean(const char* sensorType, bool value, const char* unit, const char* capabilityKey);

    // Publishes the LAST sample again, byte for byte, with the same message id (what QoS 1 redelivery does). INVALID_FIELD if none.
    Status retryLast();

    // Handles one received command payload (the commands topic): evaluates it with the firmware pipeline, applies an accepted LED
    // command, publishes the acknowledgement when one is due. Returns the disposition; lastStatus() tells whether the ack could be built.
    Disposition onCommand(const uint8_t* payload, size_t length);

    Status lastStatus() const { return last_; }
    const char* deviceId() const { return deviceId_; }
    MessageIds& ids() { return ids_; }

private:
    Status publishBuilt(const Telemetry& t);

    Hal& hal_;
    char deviceId_[37];
    char topicTelemetry_[96];
    char topicStatus_[96];
    char topicAck_[96];
    MessageIds ids_;
    bool versionedAcks_;
    Status begin_;
    Status last_;
    char lastPayload_[320];
    size_t lastLength_;
};

}  // namespace nexus

#endif
