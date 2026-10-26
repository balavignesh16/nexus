#pragma once
#include <Arduino.h>
#include <ArduinoJson.h>
#include <esp_system.h>

// USB-only. No WiFi, NTP, broker or network credentials in firmware.
class UsbProtocol {
public:
    const char* board;
    String boot, chip, session, device;
    uint32_t sequence = 0, syncedAt = 0, sentHello = 0;
    int64_t epoch = 0;
    char line[1025] = {};
    size_t used = 0;
    bool discarding = false, bound = false;
    // Fail closed after 64 distinct commands in one boot; NEVER evict and reapply an old ID.
    struct Result { String id, payload, status, reason; } history[64];
    size_t remembered = 0;
    explicit UsbProtocol(const char* identity): board(identity) {}
    bool (*apply)(const String&, JsonVariantConst, String&);
    bool (*local)(const String&) = nullptr;
    bool outputsReady = false;

    void begin(bool ready, bool (*handler)(const String&, JsonVariantConst, String&)) {
        outputsReady = ready; apply = handler;
        Serial.begin(115200);
        boot = String(esp_random(), HEX) + String(esp_random(), HEX);
        uint64_t mac = ESP.getEfuseMac();
        char id[17]; snprintf(id, sizeof(id), "%012llx", (unsigned long long)mac); chip = id;
        hello();
    }
    void emit(JsonDocument& doc) { serializeJson(doc, Serial); Serial.println(); }
    void base(JsonDocument& doc, const char* kind) {
        doc["serialVersion"] = 1; doc["kind"] = kind; doc["board"] = board;
        doc["boot"] = boot; doc["chipId"] = chip;
    }
    void hello() {
        JsonDocument doc; base(doc, "hello"); doc["firmware"] = "nexus-usb-1.0.0";
        doc["outputsReady"] = outputsReady; doc["session"] = session; doc["deviceId"] = device;
        JsonArray caps=doc["capabilities"].to<JsonArray>();
        if(String(board)=="ESP1") {caps.add("motion"); caps.add("button"); caps.add("led_state"); caps.add("led_mode"); caps.add("LED:SET_STATE");}
        if(String(board)=="ESP2") {caps.add("gas_adc"); caps.add("alarm_status"); caps.add("LOCAL_ALARM_ONLY");}
        if(String(board)=="ESP3") {caps.add("servo_requested_angle"); caps.add("led_state"); caps.add("LED:SET_STATE"); caps.add("SERVO:SET_STATE:60,90,120");}
        emit(doc); sentHello = millis();
    }
    void telemetry(JsonDocument& values) {
        JsonDocument doc; base(doc, "telemetry"); doc["sequence"] = ++sequence;
        doc["uptimeMs"] = millis(); doc["deviceId"] = device;
        doc["readings"].set(values.as<JsonObjectConst>()); emit(doc);
    }
    void ack(const String& id, const String& status, const String& reason) {
        JsonDocument doc; base(doc, "ack"); doc["deviceId"] = device; doc["session"] = session;
        doc["commandId"] = id; doc["status"] = status; doc["reason"] = reason; emit(doc);
    }
    static bool uuid(const String& s) {
        if (s.length() != 36) return false;
        for (int i=0;i<36;++i) {
            if (i==8 || i==13 || i==18 || i==23) { if(s[i]!='-') return false; }
            else if (!isxdigit((unsigned char)s[i])) return false;
        }
        return true;
    }
    void receive(const char* text) {
        if (text[0]!='{') { if(local && strlen(text)<=64) local(String(text)); return; }
        JsonDocument doc;
        if (deserializeJson(doc, text) || !doc["serialVersion"].is<int>() || doc["serialVersion"].as<int>() != 1) return;
        if (doc["board"].as<String>() != board || doc["boot"].as<String>() != boot) return;
        String kind = doc["kind"].as<String>();
        if (kind == "bind") {
            // Session cannot be replaced within the same boot, preventing a reconnect from resetting dedupe.
            if ((!bound || (doc["deviceId"].as<String>()==device && doc["session"].as<String>()==session)) && uuid(doc["deviceId"].as<String>()) && uuid(doc["session"].as<String>()) &&
                    doc["epoch"].is<int64_t>() && doc["epoch"].as<int64_t>() >= 1704067200) {
                device = doc["deviceId"].as<String>(); session = doc["session"].as<String>();
                epoch = doc["epoch"].as<int64_t>(); syncedAt = millis(); bound = true;
            }
            return;
        }
        if (kind != "command" || !bound || doc["session"].as<String>() != session || doc["deviceId"].as<String>() != device) return;
        JsonObjectConst command = doc["command"].as<JsonObjectConst>();
        String id = command["commandId"].as<String>();
        if (!uuid(id)) return;
        String payload; serializeJson(command, payload);
        for (size_t i=0;i<remembered;++i) if(history[i].id == id) {
            if (history[i].payload != payload) ack(id, "REJECTED", "COMMAND_ID_CONFLICT");
            else ack(id, history[i].status, history[i].reason);
            return;
        }
        if (remembered == 64) { ack(id, "REJECTED", "DEDUPE_CAPACITY"); return; }
        String reason, status = "REJECTED";
        if (!command["capability"].is<const char*>() || !command["command"].is<const char*>() ||
                command["command"].as<String>() != "SET_STATE" || !command["timestamp"].is<int64_t>() || command["value"].isNull()) reason = "MALFORMED";
        else if (uint32_t(millis() - syncedAt) > 3600000) reason = "CLOCK_SYNC_EXPIRED";
        else {
            int64_t age = epoch + uint32_t(millis()-syncedAt)/1000 - command["timestamp"].as<int64_t>();
            if(age > 30 || age < -30) reason = "STALE";
            else if (!outputsReady) reason = "OUTPUTS_UNVERIFIED";
            else if (apply(command["capability"].as<String>(), command["value"], reason)) { status = "ACCEPTED"; reason = "PROCESSED_PHYSICALLY_UNVERIFIED"; }
        }
        history[remembered++] = {id, payload, status, reason}; ack(id, status, reason);
    }
    void poll() {
        // Bound serial processing per loop, so a flood cannot starve ESP2's local logic.
        for (int budget=0; budget<128 && Serial.available(); ++budget) {
            char c=Serial.read();
            if(c=='\n') { if(!discarding && used) {line[used]=0; receive(line);} used=0; discarding=false; }
            else if(c!='\r' && !discarding) { if(used<1024) line[used++]=c; else {used=0; discarding=true;} }
        }
        if(uint32_t(millis()-sentHello)>=5000) hello();
    }
};
