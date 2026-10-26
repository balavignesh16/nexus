package com.nexus.intelligence.model;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Strict parsing of model output into a fixed schema. Model text is untrusted: it must be ONE JSON object (no leading or trailing content), with unique
 * member names at every depth, only the fields the schema names, every required field present with the declared type, bounded nesting and bounded string
 * length. Every refusal is a fixed {@link Code}; nothing of the text is echoed.
 */
public final class StructuredOutput {
    private StructuredOutput() {}

    public enum Code { NOT_JSON, NOT_OBJECT, DUPLICATE_KEY, TRAILING_CONTENT, UNKNOWN_FIELD, MISSING_FIELD, WRONG_TYPE, TOO_DEEP, TOO_LARGE }

    public enum FieldType { STRING, NUMBER, BOOLEAN, STRING_LIST, OBJECT, OBJECT_LIST }

    public record Schema(Map<String, FieldType> fields, Set<String> required, int maxStringChars, int maxDepth) {
        public Schema {
            fields = Map.copyOf(fields);
            required = Set.copyOf(required);
            if (!fields.keySet().containsAll(required)) throw new IllegalArgumentException("a required field is not declared");
        }

        public static Schema of(Map<String, FieldType> fields, Set<String> required) {
            return new Schema(fields, required, 2_000, 6);
        }
    }

    public record Parsed(Map<String, Object> values) {}

    public static final class Rejected extends Exception {
        private final Code code;

        Rejected(Code code) {
            super(code.name(), null, false, false);
            this.code = code;
        }

        public Code code() {
            return code;
        }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final JsonFactory STRICT = JsonFactory.builder().enable(com.fasterxml.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();

    public static Parsed parse(String text, Schema schema, int maxChars) throws Rejected {
        if (text == null) throw new Rejected(Code.NOT_JSON);
        if (text.length() > maxChars) throw new Rejected(Code.TOO_LARGE);
        JsonNode root;
        try (JsonParser parser = STRICT.createParser(text)) {
            if (parser.nextToken() == null) throw new Rejected(Code.NOT_JSON);
            root = MAPPER.readTree(parser);
            try {
                if (parser.nextToken() != null) throw new Rejected(Code.TRAILING_CONTENT);
            } catch (JsonParseException trailing) {
                throw new Rejected(Code.TRAILING_CONTENT);
            }
        } catch (JsonParseException e) {
            throw new Rejected(e.getOriginalMessage() != null && e.getOriginalMessage().startsWith("Duplicate field") ? Code.DUPLICATE_KEY : Code.NOT_JSON);
        } catch (IOException e) {
            throw new Rejected(Code.NOT_JSON);
        }
        if (root == null || !root.isObject()) throw new Rejected(Code.NOT_OBJECT);
        if (depth(root) > schema.maxDepth()) throw new Rejected(Code.TOO_DEEP);
        Iterator<String> names = root.fieldNames();
        while (names.hasNext()) if (!schema.fields().containsKey(names.next())) throw new Rejected(Code.UNKNOWN_FIELD);
        for (String required : schema.required()) if (!root.has(required) || root.get(required).isNull()) throw new Rejected(Code.MISSING_FIELD);
        Map<String, Object> values = new LinkedHashMap<>();
        for (Map.Entry<String, FieldType> field : schema.fields().entrySet()) {
            JsonNode value = root.get(field.getKey());
            if (value == null || value.isNull()) continue;
            if (!matches(value, field.getValue())) throw new Rejected(Code.WRONG_TYPE);
            if (tooLong(value, schema.maxStringChars())) throw new Rejected(Code.TOO_LARGE);
            values.put(field.getKey(), MAPPER.convertValue(value, Object.class));
        }
        return new Parsed(values);
    }

    private static boolean matches(JsonNode v, FieldType type) {
        return switch (type) {
            case STRING -> v.isTextual();
            case NUMBER -> v.isNumber();
            case BOOLEAN -> v.isBoolean();
            case STRING_LIST -> v.isArray() && allMatch(v, JsonNode::isTextual);
            case OBJECT -> v.isObject();
            case OBJECT_LIST -> v.isArray() && allMatch(v, JsonNode::isObject);
        };
    }

    private static boolean allMatch(JsonNode array, java.util.function.Predicate<JsonNode> test) {
        for (JsonNode element : array) if (!test.test(element)) return false;
        return true;
    }

    private static boolean tooLong(JsonNode v, int max) {
        if (v.isTextual()) return v.textValue().length() > max;
        for (JsonNode child : v) if (tooLong(child, max)) return true;
        return false;
    }

    private static int depth(JsonNode v) {
        int deepest = 0;
        for (JsonNode child : v) deepest = Math.max(deepest, depth(child));
        return v.isContainerNode() ? deepest + 1 : 0;
    }
}
