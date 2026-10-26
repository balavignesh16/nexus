package com.nexus.telemetry.application;

/** The entry point a telemetry sample arrived through. A diagnostic label only: MQTT and HTTP share one deduplication key space (R17). */
public enum TelemetrySource {
    MQTT, HTTP
}
