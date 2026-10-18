package com.nexus.intelligence.anomaly;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The result of analysing one device. {@code connectivity} is a SEPARATE persisted fact, shown beside the findings and never merged into them: a missing
 * reading does not mean the device is offline and an online device can stop reporting a capability.
 */
public record AnomalyReport(UUID deviceId, boolean deviceFound, Instant evaluatedAt, Finding.Window window, List<String> capabilitiesAnalysed, boolean capabilityListTruncated,
                            TelemetrySeriesReader.Connectivity connectivity, List<Finding> findings) {
    public static AnomalyReport notFound(UUID device, Instant at) {
        return new AnomalyReport(device, false, at, null, List.of(), false, null, List.of());
    }
}
