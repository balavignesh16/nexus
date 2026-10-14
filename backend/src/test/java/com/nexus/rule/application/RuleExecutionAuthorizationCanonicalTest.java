package com.nexus.rule.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Canonical value comparison used to bind a dispatched request to its persisted candidate. */
class RuleExecutionAuthorizationCanonicalTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private boolean same(String a, String b) throws Exception { return RuleExecutionAuthorization.canonicalEquals(mapper.readTree(a), mapper.readTree(b)); }

    @Test void numbersCompareByDecimalValueNotText() throws Exception {
        assertTrue(same("{\"v\":1}", "{\"v\":1.0}"));
        assertTrue(same("{\"v\":10}", "{\"v\":1E1}"));
        assertFalse(same("{\"v\":1}", "{\"v\":1.0001}"));
    }

    @Test void objectsCompareByKeySetAndNestedValuesRegardlessOfOrder() throws Exception {
        assertTrue(same("{\"a\":{\"channel\":2,\"state\":true},\"b\":1}", "{\"b\":1.0,\"a\":{\"state\":true,\"channel\":2}}"));
        assertFalse(same("{\"a\":1}", "{\"a\":1,\"b\":2}"));
        assertFalse(same("{\"a\":1,\"b\":2}", "{\"a\":1}"));
        assertFalse(same("{\"a\":1}", "{\"b\":1}"));
    }

    @Test void arraysAreOrderedAndTypesAreNeverCoerced() throws Exception {
        assertTrue(same("[1,2,3]", "[1.0,2,3]"));
        assertFalse(same("[1,2]", "[2,1]"));
        assertFalse(same("{\"v\":true}", "{\"v\":1}"), "false/true are not numbers");
        assertFalse(same("{\"v\":\"1\"}", "{\"v\":1}"), "text is not a number");
        assertFalse(same("{\"v\":0}", "{\"v\":false}"), "zero is not false");
        assertTrue(same("{\"v\":\"\"}", "{\"v\":\"\"}"), "a valid empty string is a present value");
        assertFalse(same("{\"v\":\"\"}", "{\"v\":null}"));
    }
}
