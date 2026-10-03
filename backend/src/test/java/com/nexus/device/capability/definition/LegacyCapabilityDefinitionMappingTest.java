package com.nexus.device.capability.definition;

import com.nexus.device.capability.CapabilityType;
import org.junit.jupiter.api.Test;
import java.util.Set;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.*;

class LegacyCapabilityDefinitionMappingTest {
    @Test void mapsEveryCurrentCapabilityExactlyOnce() {
        var mapping = LegacyCapabilityDefinitionMapping.all();
        assertEquals(CapabilityType.values().length, mapping.size());
        assertEquals(mapping.size(), Set.copyOf(mapping.values()).size());
        assertEquals(Set.of("temperature", "humidity", "motion", "led", "pressure", "gas",
                "button", "relay", "servo", "fan", "buzzer"),
                mapping.values().stream().collect(Collectors.toSet()));
    }
}
