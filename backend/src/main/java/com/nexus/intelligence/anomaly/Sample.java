package com.nexus.intelligence.anomaly;

import java.time.Instant;

/** One persisted numeric reading: the telemetry row id, the device's event time and the value. */
public record Sample(String id, Instant at, double value) {}
