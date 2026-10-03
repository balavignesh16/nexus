package com.nexus.device.template;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DeviceTemplateTest {
 @Test void normalizesStableMachineKey(){assertEquals("custom_mqtt_device",DeviceTemplate.normalizeKey(" Custom-MQTT Device "));}
 @Test void rejectsUnsafeKey(){assertThrows(IllegalArgumentException.class,()->DeviceTemplate.normalizeKey("123 invalid"));}
}
