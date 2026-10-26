package com.nexus.intelligence.anomaly;

import java.time.Instant;

/** A telemetry_history row as read, before any cleaning. {@code numeric} is null for a non-numeric row. */
public record RawRow(String id, Instant at, String valueType, Double numeric, String unit) {}
