// Strict command parser and the firmware's command pipeline (Messages.h, evaluateCommand).
//
// The JSON reader accepts exactly what the contract validator (sdk/python/nexus_contract.rules.parse_json) accepts: UTF-8, one JSON
// object, no duplicate member names at any depth, no NaN or Infinity, no trailing content, only space, tab, line feed and
// carriage return as whitespace. It never allocates: member positions are recorded in a fixed table and the text is decoded on demand.
// Bounds of this library, not of the contract: a payload over 512 bytes, nesting deeper than 32, more than 96 members in all, or
// more than 255 objects is refused as NO_ACK_NOT_JSON.
#include "Messages.h"

#include <string.h>

namespace nexus {

namespace {

const int kMaxDepth = 32;
const int kMaxMembers = 96;
const int kMaxObjects = 255;

enum ValueType { T_STRING, T_INT, T_FLOAT, T_TRUE, T_FALSE, T_NULL, T_OBJECT, T_ARRAY };

struct Member {
    uint16_t keyStart, keyLen;  // raw bytes between the quotes
    uint16_t valStart, valLen;  // for strings the raw bytes between the quotes; for numbers the number text
    uint8_t type;
    uint8_t object;             // 0 = the top-level object
};

// Decodes the content of a JSON string (the part between the quotes of an already validated string) one byte at a time.
class Cursor {
public:
    Cursor(const uint8_t* begin, size_t length) : p_(begin), end_(begin + length), np_(0), ip_(0) {}
    int next() {
        if (ip_ < np_) return pending_[ip_++];
        if (p_ >= end_) return -1;
        uint8_t c = *p_++;
        if (c != '\\') return c;
        uint8_t e = *p_++;
        switch (e) {
            case 'b': return '\b';
            case 'f': return '\f';
            case 'n': return '\n';
            case 'r': return '\r';
            case 't': return '\t';
            case 'u': {
                uint32_t cp = hex4();
                if (cp >= 0xD800 && cp <= 0xDBFF && end_ - p_ >= 6 && p_[0] == '\\' && p_[1] == 'u') {
                    const uint8_t* save = p_;
                    p_ += 2;
                    uint32_t low = hex4();
                    if (low >= 0xDC00 && low <= 0xDFFF) cp = 0x10000 + ((cp - 0xD800) << 10) + (low - 0xDC00);
                    else p_ = save;  // a lone high surrogate: leave the next escape for the next call
                }
                encode(cp);
                return pending_[ip_++];
            }
            default: return e;  // '"', '\\' and '/'
        }
    }

private:
    uint32_t hex4() {
        uint32_t v = 0;
        for (int i = 0; i < 4; ++i) {
            uint8_t h = *p_++;
            v = (v << 4) | (uint32_t)(h <= '9' ? h - '0' : (h | 0x20) - 'a' + 10);
        }
        return v;
    }
    void encode(uint32_t cp) {  // a lone surrogate is encoded as three bytes, so equal text always gives equal bytes
        ip_ = 0;
        if (cp < 0x80) { pending_[0] = (uint8_t)cp; np_ = 1; }
        else if (cp < 0x800) { pending_[0] = (uint8_t)(0xC0 | (cp >> 6)); pending_[1] = (uint8_t)(0x80 | (cp & 0x3F)); np_ = 2; }
        else if (cp < 0x10000) {
            pending_[0] = (uint8_t)(0xE0 | (cp >> 12)); pending_[1] = (uint8_t)(0x80 | ((cp >> 6) & 0x3F));
            pending_[2] = (uint8_t)(0x80 | (cp & 0x3F)); np_ = 3;
        } else {
            pending_[0] = (uint8_t)(0xF0 | (cp >> 18)); pending_[1] = (uint8_t)(0x80 | ((cp >> 12) & 0x3F));
            pending_[2] = (uint8_t)(0x80 | ((cp >> 6) & 0x3F)); pending_[3] = (uint8_t)(0x80 | (cp & 0x3F)); np_ = 4;
        }
    }

    const uint8_t* p_;
    const uint8_t* end_;
    uint8_t pending_[4];
    uint8_t np_, ip_;
};

bool sameText(const uint8_t* a, size_t an, const uint8_t* b, size_t bn) {
    Cursor x(a, an), y(b, bn);
    for (;;) {
        int cx = x.next(), cy = y.next();
        if (cx != cy) return false;
        if (cx < 0) return true;
    }
}

bool equalsLiteral(const uint8_t* raw, size_t n, const char* literal) {
    Cursor x(raw, n);
    for (const char* p = literal;; ++p) {
        int c = x.next();
        if (c != (*p ? (unsigned char)*p : -1)) return false;
        if (c < 0) return true;
    }
}

bool validUtf8(const uint8_t* s, size_t n) {
    size_t i = 0;
    while (i < n) {
        uint8_t c = s[i];
        if (c < 0x80) { ++i; continue; }
        size_t extra;
        uint32_t cp;
        if (c >= 0xC2 && c <= 0xDF) { extra = 1; cp = c & 0x1F; }
        else if (c >= 0xE0 && c <= 0xEF) { extra = 2; cp = c & 0x0F; }
        else if (c >= 0xF0 && c <= 0xF4) { extra = 3; cp = c & 0x07; }
        else return false;
        if (i + extra >= n) return false;
        for (size_t k = 1; k <= extra; ++k) {
            if ((s[i + k] & 0xC0) != 0x80) return false;
            cp = (cp << 6) | (s[i + k] & 0x3F);
        }
        if (extra == 2 && (cp < 0x800 || (cp >= 0xD800 && cp <= 0xDFFF))) return false;  // overlong, or an encoded surrogate
        if (extra == 3 && (cp < 0x10000 || cp > 0x10FFFF)) return false;
        i += extra + 1;
    }
    return true;
}

class Parser {
public:
    Parser(const uint8_t* s, size_t n) : s_(s), n_(n), pos_(0), members_(0), objects_(0) {}

    // true when the whole payload is one strict JSON object; false for anything else, including an over-limit document
    bool parseDocument() {
        skipWs();
        if (pos_ >= n_ || s_[pos_] != '{') return false;
        if (!parseValue(0, 0, 0)) return false;
        skipWs();
        return pos_ == n_ && noDuplicateNames();
    }

    const Member* find(const char* name) const {
        for (int i = 0; i < members_; ++i) {
            if (table_[i].object == 0 && equalsLiteral(s_ + table_[i].keyStart, table_[i].keyLen, name)) return &table_[i];
        }
        return 0;
    }
    const uint8_t* text() const { return s_; }

private:
    void skipWs() {
        while (pos_ < n_ && (s_[pos_] == ' ' || s_[pos_] == '\t' || s_[pos_] == '\n' || s_[pos_] == '\r')) ++pos_;
    }

    // Scans a string starting at the opening quote; leaves pos_ after the closing quote and reports the raw content span.
    bool scanString(uint16_t* start, uint16_t* len) {
        ++pos_;
        *start = (uint16_t)pos_;
        while (pos_ < n_) {
            uint8_t c = s_[pos_];
            if (c == '"') { *len = (uint16_t)(pos_ - *start); ++pos_; return true; }
            if (c < 0x20) return false;
            if (c == '\\') {
                if (pos_ + 1 >= n_) return false;
                uint8_t e = s_[pos_ + 1];
                if (e == 'u') {
                    if (pos_ + 5 >= n_) return false;
                    for (int k = 2; k <= 5; ++k) {
                        uint8_t h = s_[pos_ + k];
                        if (!((h >= '0' && h <= '9') || (h >= 'a' && h <= 'f') || (h >= 'A' && h <= 'F'))) return false;
                    }
                    pos_ += 6;
                    continue;
                }
                if (e != '"' && e != '\\' && e != '/' && e != 'b' && e != 'f' && e != 'n' && e != 'r' && e != 't') return false;
                pos_ += 2;
                continue;
            }
            ++pos_;
        }
        return false;
    }

    bool scanNumber(bool* isInt, uint16_t* start, uint16_t* len) {
        *start = (uint16_t)pos_;
        *isInt = true;
        if (pos_ < n_ && s_[pos_] == '-') ++pos_;
        if (pos_ >= n_) return false;
        if (s_[pos_] == '0') ++pos_;
        else if (s_[pos_] >= '1' && s_[pos_] <= '9') { while (pos_ < n_ && isDigit(s_[pos_])) ++pos_; }
        else return false;
        if (pos_ < n_ && s_[pos_] == '.') {
            *isInt = false;
            ++pos_;
            if (pos_ >= n_ || !isDigit(s_[pos_])) return false;
            while (pos_ < n_ && isDigit(s_[pos_])) ++pos_;
        }
        if (pos_ < n_ && (s_[pos_] == 'e' || s_[pos_] == 'E')) {
            *isInt = false;
            ++pos_;
            if (pos_ < n_ && (s_[pos_] == '+' || s_[pos_] == '-')) ++pos_;
            if (pos_ >= n_ || !isDigit(s_[pos_])) return false;
            while (pos_ < n_ && isDigit(s_[pos_])) ++pos_;
        }
        *len = (uint16_t)(pos_ - *start);
        return true;
    }

    static bool isDigit(uint8_t c) { return c >= '0' && c <= '9'; }

    bool literal(const char* word) {
        size_t k = strlen(word);
        if (n_ - pos_ < k || memcmp(s_ + pos_, word, k) != 0) return false;
        pos_ += k;
        return true;
    }

    // Parses one value. For a member of an object the caller passes the slot to fill (type and value span).
    bool parseValue(int depth, Member* slot, int objectId) {
        (void)objectId;
        if (depth >= kMaxDepth || pos_ >= n_) return false;
        uint8_t c = s_[pos_];
        uint16_t a = 0, b = 0;
        uint8_t type;
        if (c == '{') {
            if (!parseObject(depth)) return false;
            type = T_OBJECT;
        } else if (c == '[') {
            if (!parseArray(depth)) return false;
            type = T_ARRAY;
        } else if (c == '"') {
            if (!scanString(&a, &b)) return false;
            type = T_STRING;
        } else if (c == 't') { if (!literal("true")) return false; type = T_TRUE; }
        else if (c == 'f') { if (!literal("false")) return false; type = T_FALSE; }
        else if (c == 'n') { if (!literal("null")) return false; type = T_NULL; }
        else {
            bool isInt;
            if (!scanNumber(&isInt, &a, &b)) return false;
            type = isInt ? T_INT : T_FLOAT;
        }
        if (slot) { slot->type = type; slot->valStart = a; slot->valLen = b; }
        return true;
    }

    bool parseObject(int depth) {
        if (objects_ >= kMaxObjects) return false;
        uint8_t id = (uint8_t)objects_++;
        ++pos_;  // {
        skipWs();
        if (pos_ < n_ && s_[pos_] == '}') { ++pos_; return true; }
        for (;;) {
            skipWs();
            if (pos_ >= n_ || s_[pos_] != '"' || members_ >= kMaxMembers) return false;
            Member* m = &table_[members_++];
            m->object = id;
            if (!scanString(&m->keyStart, &m->keyLen)) return false;
            skipWs();
            if (pos_ >= n_ || s_[pos_] != ':') return false;
            ++pos_;
            skipWs();
            if (!parseValue(depth + 1, m, id)) return false;
            skipWs();
            if (pos_ >= n_) return false;
            if (s_[pos_] == ',') { ++pos_; continue; }
            if (s_[pos_] == '}') { ++pos_; return true; }
            return false;
        }
    }

    bool parseArray(int depth) {
        ++pos_;  // [
        skipWs();
        if (pos_ < n_ && s_[pos_] == ']') { ++pos_; return true; }
        for (;;) {
            skipWs();
            if (!parseValue(depth + 1, 0, 0)) return false;
            skipWs();
            if (pos_ >= n_) return false;
            if (s_[pos_] == ',') { ++pos_; continue; }
            if (s_[pos_] == ']') { ++pos_; return true; }
            return false;
        }
    }

    bool noDuplicateNames() const {
        for (int i = 0; i < members_; ++i) {
            for (int j = i + 1; j < members_; ++j) {
                if (table_[i].object == table_[j].object &&
                    sameText(s_ + table_[i].keyStart, table_[i].keyLen, s_ + table_[j].keyStart, table_[j].keyLen)) return false;
            }
        }
        return true;
    }

    const uint8_t* s_;
    size_t n_, pos_;
    int members_, objects_;
    Member table_[kMaxMembers];
};

// An integer literal that fits a 32-bit C long (the firmware's is<long>() on the ESP32). Digits beyond ten are out of range already.
bool int32Value(const uint8_t* s, const Member& m, int64_t* out) {
    size_t i = m.valStart, end = m.valStart + m.valLen;
    bool negative = false;
    if (s[i] == '-') { negative = true; ++i; }
    int64_t v = 0;
    for (; i < end; ++i) {
        v = v * 10 + (s[i] - '0');
        if (v > 2147483648LL) return false;
    }
    if (negative) v = -v;
    if (v < -2147483648LL || v > 2147483647LL) return false;
    *out = v;
    return true;
}

}  // namespace

CommandResult evaluateCommand(const uint8_t* payload, size_t length, int64_t now) {
    CommandResult r;
    r.disposition = NO_ACK_NOT_JSON;
    r.commandId[0] = 0;
    r.value = false;
    r.timestamp = 0;

    if (!clockIsSynced(now)) { r.disposition = NO_ACK_CLOCK_NOT_SYNCED; return r; }
    if (payload == 0 || length == 0 || length > kMaxCommandPayload || !validUtf8(payload, length)) return r;
    Parser parser(payload, length);
    if (!parser.parseDocument()) return r;

    const Member* id = parser.find("commandId");
    if (id == 0 || id->type != T_STRING) { r.disposition = NO_ACK_NO_COMMAND_ID; return r; }
    char decoded[40];
    size_t n = 0;
    {
        Cursor c(payload + id->valStart, id->valLen);
        for (int ch = c.next(); ch >= 0; ch = c.next()) {
            if (n >= 37) break;
            decoded[n++] = (char)ch;
        }
    }
    if (n != 36 || !isUuidText(decoded, n)) { r.disposition = NO_ACK_NO_COMMAND_ID; return r; }
    memcpy(r.commandId, decoded, 36);
    r.commandId[36] = 0;

    const Member* capability = parser.find("capability");
    const Member* command = parser.find("command");
    const Member* value = parser.find("value");
    const Member* timestamp = parser.find("timestamp");
    if (capability == 0 || capability->type != T_STRING || command == 0 || command->type != T_STRING || value == 0 ||
        (value->type != T_TRUE && value->type != T_FALSE) || timestamp == 0 || timestamp->type != T_INT) {
        r.disposition = REJECTED_MALFORMED;
        return r;
    }
    int64_t commandTimestamp;
    if (!int32Value(payload, *timestamp, &commandTimestamp)) { r.disposition = REJECTED_MALFORMED; return r; }
    r.timestamp = commandTimestamp;
    r.value = value->type == T_TRUE;

    if (!equalsLiteral(payload + capability->valStart, capability->valLen, "LED") ||
        !equalsLiteral(payload + command->valStart, command->valLen, "SET_STATE")) {
        r.disposition = REJECTED_UNSUPPORTED;
        return r;
    }
    if (isStale(now, commandTimestamp)) { r.disposition = REJECTED_STALE; return r; }
    r.disposition = ACCEPTED;
    return r;
}

}  // namespace nexus
