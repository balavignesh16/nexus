package com.nexus.action.outbox;

import java.util.UUID;

/**
 * A manual command was refused because accepting it would exceed a queue cap (contract R18). Thrown by acceptance BEFORE any durable
 * write, so nothing (no ActionHistory row, desired state, outbox row or MQTT publication) exists for the refused command; the REST
 * layer answers HTTP 429 with the stable code {@code COMMAND_QUEUE_FULL}. It is deliberately not a CommandRejectedException: that one is
 * turned into a stored FAILURE result, this one is a typed refusal. A rule-originated command over a cap is recorded as a visible
 * suppression instead (the rule candidate is DENIED with the reason), never as this exception.
 */
public class CommandQueueFullException extends RuntimeException {
    public static final String CODE = "COMMAND_QUEUE_FULL";

    public enum Scope { GLOBAL, DEVICE }

    private final Scope scope;
    private final int limit;
    private final UUID deviceId;

    public CommandQueueFullException(Scope scope, int limit, UUID deviceId) {
        super(scope == Scope.GLOBAL
                ? "The command queue is full (" + limit + " unresolved commands); try again shortly"
                : "The command queue of this device is full (" + limit + " unresolved commands); try again shortly");
        this.scope = scope;
        this.limit = limit;
        this.deviceId = deviceId;
    }

    public Scope scope() { return scope; }
    public int limit() { return limit; }
    public UUID deviceId() { return deviceId; }
    public String code() { return CODE; }
}
