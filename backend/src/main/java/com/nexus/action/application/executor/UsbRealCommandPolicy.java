package com.nexus.action.application.executor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.util.*;

/** Explicit bench-only opt-in. Default denies every REAL device; never grants capability/ownership/connectivity. */
@Component
public final class UsbRealCommandPolicy {
    private final Set<UUID> leds;
    private final Set<UUID> servos;
    public UsbRealCommandPolicy(
            @Value("${nexus.command.usb-real-led-devices:${NEXUS_USB_REAL_LED_DEVICES:}}") String ledDevices,
            @Value("${nexus.command.usb-real-servo-devices:${NEXUS_USB_REAL_SERVO_DEVICES:}}") String servoDevices) {
        leds=parse(ledDevices); servos=parse(servoDevices);
    }
    private static Set<UUID> parse(String text) {
        Set<UUID> ids=new HashSet<>();
        if(text!=null && !text.isBlank()) for(String id:text.split(",")) ids.add(UUID.fromString(id.trim()));
        return Set.copyOf(ids);
    }
    public void requireAllowed(UUID device, Map<String,Object> parameters) {
        if(!leds.contains(device) && !servos.contains(device))
            throw new CommandExecutor.CommandRejectedException("Command dispatch to REAL devices is not yet enabled");
        Object legacy=parameters.get("capability"), key=parameters.get("capabilityKey");
        String cap=String.valueOf(legacy!=null?legacy:key).toUpperCase(Locale.ROOT);
        if(legacy!=null && key!=null && !String.valueOf(legacy).equalsIgnoreCase(String.valueOf(key)))
            throw new CommandExecutor.CommandRejectedException("USB REAL capability identities conflict");
        Object value=parameters.get("value");
        if("SET_STATE".equals(parameters.get("command"))) {
            if("LED".equals(cap) && leds.contains(device) && value instanceof Boolean) return;
            if("SERVO".equals(cap) && servos.contains(device) && (value instanceof Integer || value instanceof Long)
                    && Set.of(60L,90L,120L).contains(((Number)value).longValue())) return;
        }
        throw new CommandExecutor.CommandRejectedException("USB REAL bench profile refuses capability, command or value");
    }
}
