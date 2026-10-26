#include "../common/Protocol.h"
#include "../common/Logic.h"
#ifndef ESP3_POWER_VERIFIED
#define ESP3_POWER_VERIFIED 0
#endif
UsbProtocol usb("ESP3");
uint32_t reported=0;
int angle=90;
bool light=false;
void setServo(int target) {
    angle=target;
    // Arduino ESP32 2.0.17 LEDC: bounded 60/90/120-degree tested positions, no fan or relay.
    uint32_t pulse=1000+uint32_t(target)*1000/180; // Conservative pulse envelope; physical travel must be checked.
    ledcWrite(0,pulse*65535/20000);
}
bool local(const String& text) {
    if(!ESP3_POWER_VERIFIED) return false;
    if(text=="CENTER") setServo(90);
    else if(text=="OPEN" || text=="OPEN LIGHT ON" || text=="OPEN LIGHT OFF") setServo(120);
    else if(text=="CLOSE" || text=="CLOSE LIGHT ON" || text=="CLOSE LIGHT OFF") setServo(60);
    else if(text!="LIGHT ON" && text!="LIGHT OFF") return false;
    if(text.endsWith("LIGHT ON")) {light=true; digitalWrite(12,HIGH);}
    if(text.endsWith("LIGHT OFF")) {light=false; digitalWrite(12,LOW);}
    return true;
}
bool command(const String& capability, JsonVariantConst value, String& reason) {
    if(capability=="LED" && value.is<bool>()) {light=value.as<bool>(); digitalWrite(12,light?HIGH:LOW); return true;}
    if(capability=="SERVO" && value.is<int>() && !value.is<bool>() &&
            (value.as<int>()==60 || value.as<int>()==90 || value.as<int>()==120)) {setServo(value.as<int>()); return true;}
    reason="UNSUPPORTED_OR_INVALID_VALUE"; return false;
}
void setup() {
    if(ESP3_POWER_VERIFIED) {
        digitalWrite(12,LOW); pinMode(12,OUTPUT);
        ledcSetup(0,50,16); ledcAttachPin(13,0); setServo(90);
    }
    usb.begin(ESP3_POWER_VERIFIED,command);
    usb.local=local;
}
void loop() {
    usb.poll();
    if(usbv1::elapsed(millis(),reported,500)) {
        reported=millis(); JsonDocument values; values["outputs_ready"]=bool(ESP3_POWER_VERIFIED);
        if(ESP3_POWER_VERIFIED) {values["servo_requested_angle"]=angle; values["led_state"]=light;}
        // Requested angle is not independent position measurement.
        usb.telemetry(values);
    }
}
