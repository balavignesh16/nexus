package com.nexus.realtime;

/**
 * Constants for all STOMP broadcast topic destinations.
 * 
 * Topic multiplexing strategy: Only 6 platform-level topics exist.
 * Clients filter by deviceId on the client side.
 * No per-device topics are created to avoid broker subscription explosion.
 */
public final class RealtimeTopics {

    private RealtimeTopics() {}

    /** All telemetry events. Clients filter by payload.deviceId. */
    public static final String TELEMETRY = "/topic/telemetry";

    /** Digital Twin delta updates. */
    public static final String TWINS = "/topic/twins";

    /** Domain events (sensor threshold, anomaly, etc.). */
    public static final String EVENTS = "/topic/events";

    /** Rule match notifications. */
    public static final String RULES = "/topic/rules";

    /** Action execution results. */
    public static final String ACTIONS = "/topic/actions";

    /** Platform-wide health status. */
    public static final String HEALTH = "/topic/platform/health";

    /**
     * Physical MQTT connectivity (ONLINE/OFFLINE) per device (Tier 5B).
     * Deliberately distinct from TWINS (telemetry-derived sensor state) and
     * from any device lifecycle status - see DeviceConnectivityStatus.
     */
    public static final String CONNECTIVITY = "/topic/devices/connectivity";

    /**
     * Pipeline execution traces.
     * Foundation for F7.5 Pipeline Visualization.
     * Each trace message carries a correlationId linking
     * MQTT_IN → TELEMETRY → TWIN → RULE → ACTION stages.
     */
    public static final String PIPELINE = "/topic/pipeline";
}
