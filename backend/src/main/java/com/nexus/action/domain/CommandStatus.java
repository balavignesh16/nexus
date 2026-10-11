package com.nexus.action.domain;

import java.time.Clock;

/** API projection of durable publication and device ACK facts. */
public enum CommandStatus {
    ACCEPTED_FOR_DISPATCH, PUBLISHED, ACKNOWLEDGED, REJECTED, FAILED, TIMED_OUT;

    public static CommandStatus from(ActionHistoryRecord action, Clock clock) {
        if (action.getAckStatus() == AcknowledgementStatus.REJECTED) return REJECTED;
        if (action.getAckStatus() == AcknowledgementStatus.ACCEPTED) return ACKNOWLEDGED;
        if (action.getStatus() == ExecutionStatus.FAILURE || action.getStatus() == ExecutionStatus.IGNORED
                || action.getStatus() == ExecutionStatus.UNSUPPORTED) return FAILED;
        if (action.getStatus() == ExecutionStatus.SUCCESS) {
            if (action.getAckDeadlineAt() != null && clock.instant().isAfter(action.getAckDeadlineAt())) return TIMED_OUT;
            return PUBLISHED;
        }
        return ACCEPTED_FOR_DISPATCH;
    }
}
