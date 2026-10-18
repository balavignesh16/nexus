package com.nexus.adapter.conformance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.contract.PythonOracle;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The shared table's verdicts are the standalone validator's, re-derived on every run (so the table is not a second authority). */
class SharedCasesOracleTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    static String verdict(JsonNode result) {
        for (String layer : new String[]{"version", "structural"}) {
            JsonNode entry = result.get("layers").get(layer);
            if (entry != null && entry.get(0).asText().equals("FAIL")) return "REJECT:" + entry.get(1).asText();
        }
        return "ACCEPT";
    }

    @Test
    void everyExpectedVerdictOfTheSharedTableIsTheValidatorsVerdict() throws Exception {
        List<SharedCases.Case> all = new ArrayList<>();
        all.addAll(SharedCases.telemetry());
        all.addAll(SharedCases.acknowledgements());
        all.addAll(SharedCases.statuses());
        List<Map<String, Object>> items = new ArrayList<>();
        for (SharedCases.Case c : all) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", c.id());
            item.put("kind", switch (c.kind()) {
                case TELEMETRY -> "telemetry";
                case ACKNOWLEDGEMENT -> "acknowledgement";
                case STATUS -> "status";
            });
            item.put("payloadBase64", Base64.getEncoder().encodeToString(c.payload()));
            items.add(item);
        }
        Path manifest = Files.createTempFile("shared-cases", ".json");
        JSON.writeValue(manifest.toFile(), Map.of("schemaVersion", 1, "items", items));
        Map<String, JsonNode> python = PythonOracle.validateCorpus(manifest);
        Files.deleteIfExists(manifest);

        List<String> disagreements = new ArrayList<>();
        int accepting = 0, rejecting = 0;
        for (SharedCases.Case c : all) {
            String actual = verdict(python.get(c.id()));
            if (c.accepts()) accepting++; else rejecting++;
            if (!actual.equals(c.expected())) disagreements.add(c.id() + " table=" + c.expected() + " validator=" + actual);
        }
        assertTrue(accepting >= 12 && rejecting >= 15, "both verdicts are exercised: " + accepting + "/" + rejecting);
        assertEquals(List.of(), disagreements);
    }
}
