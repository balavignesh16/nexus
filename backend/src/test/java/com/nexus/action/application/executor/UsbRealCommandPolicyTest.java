package com.nexus.action.application.executor;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class UsbRealCommandPolicyTest {
    private final UUID led=UUID.randomUUID(), servo=UUID.randomUUID();
    private Map<String,Object> request(String cap,Object value) {
        return Map.of("capability",cap,"command","SET_STATE","value",value);
    }
    @Test void disabledByDefaultAndNeverGrantsAnotherDevice() {
        assertThrows(CommandExecutor.CommandRejectedException.class,()->new UsbRealCommandPolicy("","").requireAllowed(led,request("LED",true)));
        assertThrows(CommandExecutor.CommandRejectedException.class,()->new UsbRealCommandPolicy(led.toString(),"").requireAllowed(servo,request("LED",true)));
    }
    @Test void onlyExactBenchDevicesCapabilitiesAndTypedValuesAreAllowed() {
        var policy=new UsbRealCommandPolicy(led.toString(),servo.toString());
        policy.requireAllowed(led,request("LED",true));
        for(int angle:new int[]{60,90,120}) policy.requireAllowed(servo,request("SERVO",angle));
        for(var r:List.of(request("BUZZER",true),request("SERVO",60),request("LED",1)))
            assertThrows(CommandExecutor.CommandRejectedException.class,()->policy.requireAllowed(led,r));
        for(Object angle:List.of(0,180,90.0, true))
            assertThrows(CommandExecutor.CommandRejectedException.class,()->policy.requireAllowed(servo,request("SERVO",angle)));
        assertThrows(IllegalArgumentException.class,()->new UsbRealCommandPolicy("not-a-uuid",""));
    }
}
