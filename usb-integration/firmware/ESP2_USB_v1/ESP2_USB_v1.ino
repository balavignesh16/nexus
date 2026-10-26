#include "../common/Protocol.h"
#include "../common/Logic.h"
// DO NOT FLASH until wiring, polarity, baseline-in-clean-air and power are verified.
// Disabled build deliberately leaves unknown output circuits undriven; this is NOT a guaranteed buzzer-off level.
#ifndef ESP2_WIRING_VERIFIED
#define ESP2_WIRING_VERIFIED 0
#endif
#if ESP2_WIRING_VERIFIED && (!defined(ESP2_RGB_ACTIVE_HIGH) || !defined(ESP2_RELAY_ACTIVE_HIGH))
#error "Explicit RGB and relay polarity required"
#endif
#ifndef ESP2_RGB_ACTIVE_HIGH
#define ESP2_RGB_ACTIVE_HIGH 0
#endif
#ifndef ESP2_RELAY_ACTIVE_HIGH
#define ESP2_RELAY_ACTIVE_HIGH 0
#endif
UsbProtocol usb("ESP2");
uint32_t sampled=0, reported=0;
int samples=0, baseline=0, state=0, raw=0;
int64_t sum=0;
bool warmed=false;
bool command(const String&, JsonVariantConst, String& reason) {
    reason="LOCAL_ALARM_ONLY"; return false; // The backend cannot mute or force this alarm.
}
int demo=-1;
uint32_t demoAt=0;
bool local(const String& text) {
    if(!ESP2_WIRING_VERIFIED) return false;
    if(text=="AUTO") {demo=-1; return true;}
    if(text=="NORMAL") demo=0;
    else if(text=="WARNING") demo=1;
    else if(text=="ALARM") demo=2;
    else return false;
    demoAt=millis(); return true;
}
void output(int pin, bool on, bool high) { digitalWrite(pin,on==high?HIGH:LOW); }
void setup() {
    if(ESP2_WIRING_VERIFIED) {
        for(int pin:{26,25,27}) {output(pin,false,ESP2_RGB_ACTIVE_HIGH); pinMode(pin,OUTPUT);}
        output(13,false,ESP2_RELAY_ACTIVE_HIGH); pinMode(13,OUTPUT);
        analogReadResolution(12); pinMode(34,INPUT);
    }
    usb.begin(ESP2_WIRING_VERIFIED,command);
    usb.local=local;
}
void loop() {
    // Local sensing and outputs never depend on USB bind, MQTT, backend or command processing.
    if(!warmed && millis()>=120000) warmed=true;
    if(ESP2_WIRING_VERIFIED && warmed && usbv1::elapsed(millis(),sampled,100)) {
        sampled=millis(); raw=analogRead(34);
        if(samples<30) {sum+=raw; ++samples; if(samples==30) baseline=sum/30;}
        else state=usbv1::severity(state,raw-baseline);
    }
    if(demo>=0 && usbv1::elapsed(millis(),demoAt,10000)) demo=-1;
    int displayed=demo>=0?demo:state;
    if(ESP2_WIRING_VERIFIED) {
        output(26,displayed>0,ESP2_RGB_ACTIVE_HIGH);
        output(25,(demo>=0 || samples==30) && displayed<2,ESP2_RGB_ACTIVE_HIGH);
        output(27,false,ESP2_RGB_ACTIVE_HIGH);
        output(13,displayed==2,ESP2_RELAY_ACTIVE_HIGH);
    }
    usb.poll();
    if(usbv1::elapsed(millis(),reported,1000)) {
        reported=millis(); JsonDocument values;
        values["alarm_status"]=!ESP2_WIRING_VERIFIED?"WIRING_UNVERIFIED":samples<30 && demo<0?"INITIALIZING":displayed==0?"NORMAL":displayed==1?"WARNING":"ALARM";
        values["alarm_demo_override"]=demo>=0;
        if(ESP2_WIRING_VERIFIED) values["buzzer_requested"]=displayed==2;
        if(ESP2_WIRING_VERIFIED && samples==30) {values["gas_adc"]=raw; values["gas_baseline_adc"]=baseline;}
        usb.telemetry(values);
    }
}
