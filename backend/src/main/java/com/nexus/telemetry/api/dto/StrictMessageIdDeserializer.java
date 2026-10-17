package com.nexus.telemetry.api.dto;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;

import java.io.IOException;

/**
 * A telemetry messageId must be a JSON string. Jackson's default String coercion would silently turn the number 123 or
 * the boolean true into "123" or "true", which would let a malformed id pass as a valid one (R17, decision A1), so any
 * other token is refused. A JSON null never reaches this class (Jackson maps it to null), which is the same as an
 * absent field: no deduplication.
 */
public final class StrictMessageIdDeserializer extends JsonDeserializer<String> {
    @Override
    public String deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        if (parser.currentToken() != JsonToken.VALUE_STRING) {
            throw MismatchedInputException.from(parser, String.class, "messageId must be a JSON string");
        }
        return parser.getText();
    }
}
