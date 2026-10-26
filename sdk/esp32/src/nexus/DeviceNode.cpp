#include "DeviceNode.h"

#include <string.h>

namespace nexus {

DeviceNode::DeviceNode(Hal& hal, const char* deviceId, uint64_t bootId, bool contractVersionedAcks)
    : hal_(hal), ids_(bootId), versionedAcks_(contractVersionedAcks), begin_(OK), last_(OK), lastLength_(0) {
    deviceId_[0] = topicTelemetry_[0] = topicStatus_[0] = topicAck_[0] = lastPayload_[0] = 0;
    if (deviceId == 0 || !isUuidText(deviceId, strlen(deviceId))) { begin_ = INVALID_DEVICE_ID; return; }
    memcpy(deviceId_, deviceId, 36);
    deviceId_[36] = 0;
    topicTelemetry(topicTelemetry_, sizeof(topicTelemetry_), deviceId_);
    topicStatus(topicStatus_, sizeof(topicStatus_), deviceId_);
    topicAck(topicAck_, sizeof(topicAck_), deviceId_);
}

Status DeviceNode::publishStatus(bool online) {
    if (begin_ != OK) return last_ = begin_;
    const char* text = statusPayload(online);
    hal_.publish(topicStatus_, text, strlen(text), true);
    return last_ = OK;
}

Status DeviceNode::publishBuilt(const Telemetry& t) {
    size_t length = 0;
    char payload[sizeof(lastPayload_)];
    Status st = buildTelemetry(payload, sizeof(payload), &length, hal_.nowEpochSeconds(), t);
    if (st != OK) return st;
    memcpy(lastPayload_, payload, length + 1);
    lastLength_ = length;
    hal_.publish(topicTelemetry_, lastPayload_, lastLength_, false);
    return OK;
}

Status DeviceNode::publishNumber(const char* sensorType, double value, const char* unit) {
    if (begin_ != OK) return last_ = begin_;
    // The clock is checked before an id is consumed, so a refused sample does not burn a counter value.
    if (!clockIsSynced(hal_.nowEpochSeconds())) return last_ = CLOCK_NOT_SYNCED;
    MessageIds probe = ids_;
    char id[37];
    Status st = probe.next(id, sizeof(id));
    if (st != OK) return last_ = st;
    st = publishBuilt(Telemetry::ofNumber(deviceId_, sensorType, value, unit, id));
    if (st == OK) ids_ = probe;  // consumed only once the sample was built and handed over
    return last_ = st;
}

Status DeviceNode::publishBoolean(const char* sensorType, bool value, const char* unit, const char* capabilityKey) {
    if (begin_ != OK) return last_ = begin_;
    if (!clockIsSynced(hal_.nowEpochSeconds())) return last_ = CLOCK_NOT_SYNCED;
    MessageIds probe = ids_;
    char id[37];
    Status st = probe.next(id, sizeof(id));
    if (st != OK) return last_ = st;
    Telemetry t = Telemetry::ofBoolean(deviceId_, sensorType, value, unit, id);
    t.capabilityKey = capabilityKey;
    st = publishBuilt(t);
    if (st == OK) ids_ = probe;
    return last_ = st;
}

Status DeviceNode::retryLast() {
    if (begin_ != OK) return last_ = begin_;
    if (lastLength_ == 0) return last_ = INVALID_FIELD;
    hal_.publish(topicTelemetry_, lastPayload_, lastLength_, false);
    return last_ = OK;
}

Disposition DeviceNode::onCommand(const uint8_t* payload, size_t length) {
    last_ = OK;
    if (begin_ != OK) { last_ = begin_; return NO_ACK_NOT_JSON; }
    int64_t now = hal_.nowEpochSeconds();
    CommandResult r = evaluateCommand(payload, length, now);
    if (r.disposition == ACCEPTED) hal_.setLed(r.value);  // the firmware applies the LED, then acknowledges
    if (dispositionAcknowledges(r.disposition)) {
        char ack[200];
        size_t n = 0;
        last_ = buildAck(ack, sizeof(ack), &n, now, r.commandId, dispositionAckStatus(r.disposition), dispositionAckReason(r.disposition),
                         versionedAcks_);
        if (last_ == OK) hal_.publish(topicAck_, ack, n, false);
    }
    return r.disposition;
}

}  // namespace nexus
