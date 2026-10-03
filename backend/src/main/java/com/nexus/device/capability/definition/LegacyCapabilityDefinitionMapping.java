package com.nexus.device.capability.definition;

import com.nexus.device.capability.CapabilityType;
import java.util.EnumMap;
import java.util.Map;

public final class LegacyCapabilityDefinitionMapping {
    private static final Map<CapabilityType, String> KEYS = new EnumMap<>(CapabilityType.class);
    static {
        for (CapabilityType type : CapabilityType.values()) {
            KEYS.put(type, type.name().toLowerCase(java.util.Locale.ROOT));
        }
        if (KEYS.size() != CapabilityType.values().length
                || KEYS.values().stream().distinct().count() != KEYS.size()) {
            throw new IllegalStateException("Every CapabilityType must map to one unique definition key");
        }
    }
    private LegacyCapabilityDefinitionMapping() {}
    public static String keyFor(CapabilityType type) {
        return KEYS.get(java.util.Objects.requireNonNull(type, "type"));
    }
    public static Map<CapabilityType, String> all() {
        return Map.copyOf(KEYS);
    }
}
