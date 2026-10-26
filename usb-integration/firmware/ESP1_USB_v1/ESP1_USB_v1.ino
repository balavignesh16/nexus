#include "../common/Protocol.h"
#include "../common/Logic.h"
UsbProtocol usb("ESP1");
usbv1::Living living;
uint32_t reported=0;
bool led=false;
bool local(const String& text) {
    if(text=="AUTO") living.mode=0;
    else if(text=="FORCE ON") living.mode=1;
    else if(text=="FORCE OFF") living.mode=2;
    else return false;
    return true;
}
bool command(const String& capability, JsonVariantConst value, String& reason) {
    if(capability!="LED") {reason="UNSUPPORTED"; return false;}
    if(!value.is<bool>()) {reason="MALFORMED"; return false;}
    living.mode=value.as<bool>() ? 1 : 2; // SET_STATE is an explicit force mode, not a momentary pulse.
    led=value.as<bool>(); digitalWrite(26, led ? HIGH:LOW); return true;
}
void setup() {
    pinMode(14, INPUT); pinMode(33, INPUT_PULLUP);
    digitalWrite(26, LOW); pinMode(26, OUTPUT);
    usb.begin(true, command);
    usb.local=local;
}
void loop() {
    bool motion=digitalRead(14)==HIGH, pressed=digitalRead(33)==LOW;
    led=living.step(millis(), motion, pressed); digitalWrite(26, led?HIGH:LOW);
    usb.poll();
    if(usbv1::elapsed(millis(),reported,500)) {
        reported=millis(); JsonDocument values;
        values["motion"]=motion; values["button"]=pressed; values["led_state"]=led;
        values["led_mode"]=living.mode==0?"AUTO":living.mode==1?"FORCE_ON":"FORCE_OFF";
        usb.telemetry(values);
    }
}
