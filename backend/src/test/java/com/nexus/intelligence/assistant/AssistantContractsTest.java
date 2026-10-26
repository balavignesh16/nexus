package com.nexus.intelligence.assistant;

import com.nexus.intelligence.model.StructuredOutput;
import com.nexus.intelligence.retrieval.LookupRequest;
import com.nexus.intelligence.retrieval.LookupType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The two model task contracts: the instructions name the whole fixed catalogue, and both outputs are validated by code before anything uses them. */
class AssistantContractsTest {
    private static final String QUESTION = "What is the latest temperature of Boiler 1 and why did rule 3f2a9c1e-5b7d-4e0a-9c11-2d8f6a4b7e30 not fire?";

    private static Map<String, Object> lookup(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private static Map<String, Object> plan(List<Map<String, Object>> lookups, String refusal) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("lookups", lookups);
        if (refusal != null) m.put("refusal", refusal);
        return m;
    }

    private static ToolPlanParser.Code rejected(Map<String, Object> values) {
        ToolPlanParser.Result r = ToolPlanParser.validate(values, QUESTION);
        assertThat(r).isInstanceOf(ToolPlanParser.Rejected.class);
        return ((ToolPlanParser.Rejected) r).code();
    }

    @Test
    void p6_164_theInstructionsNameEveryFixedLookupAndTheSelectionRulesAndTheContractHashIsStable() {
        String routing = AssistantContracts.routingInstruction();
        for (LookupType t : LookupType.values()) assertThat(routing).contains(t.name());
        assertThat(routing).contains("WRITE_REQUEST").contains("OUT_OF_SCOPE").contains("untrusted").contains("Never invent");
        String selection = AssistantContracts.selectionInstruction();
        assertThat(selection).contains("abstain").contains("Never invent a reference").contains("untrusted");
        assertThat(AssistantContracts.contractHash()).hasSize(16).isEqualTo(AssistantContracts.contractHash());
        assertThat(AssistantContracts.ROUTING_SCHEMA.required()).containsExactly("lookups");
    }

    @Test
    void p6_181_aValidPlanIsAcceptedWithTheParametersTheQuestionSupports() {
        UUID rule = UUID.fromString("3f2a9c1e-5b7d-4e0a-9c11-2d8f6a4b7e30");
        ToolPlanParser.Result r = ToolPlanParser.validate(plan(List.of(
                lookup("type", "LATEST_TELEMETRY", "deviceName", "boiler 1", "capabilityKey", "temperature", "lookbackHours", 6),
                lookup("type", "RULE_MATCHES", "ruleId", rule.toString().toUpperCase(), "deviceName", "Boiler 1", "limit", 20.0)), null), QUESTION);
        assertThat(r).isInstanceOf(ToolPlanParser.Accepted.class);
        List<LookupRequest> requests = ((ToolPlanParser.Accepted) r).plan().requests();
        assertThat(requests).hasSize(2);
        assertThat(requests.get(0).type()).isEqualTo(LookupType.LATEST_TELEMETRY);
        assertThat(requests.get(0).lookbackHours()).isEqualTo(6);
        assertThat(requests.get(1).ruleId()).isEqualTo(rule);
        assertThat(requests.get(1).limit()).isEqualTo(20);
        // duplicates collapse
        ToolPlanParser.Result dup = ToolPlanParser.validate(plan(List.of(lookup("type", "DEVICE_STATE", "deviceName", "Boiler 1"), lookup("type", "DEVICE_STATE", "deviceName", "Boiler 1")), null), QUESTION);
        assertThat(((ToolPlanParser.Accepted) dup).plan().requests()).hasSize(1);
    }

    @Test
    void p6_181_p6_185_aPlanThatReachesOutsideTheCatalogueOrTheQuestionIsRejectedWhole() {
        assertThat(rejected(plan(List.of(lookup("type", "DELETE_ALL_RULES")), null))).isEqualTo(ToolPlanParser.Code.UNKNOWN_LOOKUP_TYPE);
        assertThat(rejected(plan(List.of(lookup("type", "COMMAND_EXECUTE", "deviceName", "Boiler 1")), null))).isEqualTo(ToolPlanParser.Code.UNKNOWN_LOOKUP_TYPE);
        assertThat(rejected(plan(List.of(lookup("type", "DEVICE_STATE", "deviceName", "Vault door 9")), null))).as("a device the question never named").isEqualTo(ToolPlanParser.Code.DEVICE_NAME_NOT_IN_QUESTION);
        assertThat(rejected(plan(List.of(lookup("type", "RULE_MATCHES", "ruleId", UUID.randomUUID().toString())), null))).isEqualTo(ToolPlanParser.Code.ID_NOT_IN_QUESTION);
        assertThat(rejected(plan(List.of(lookup("type", "RULE_MATCHES", "ruleId", "not-a-uuid")), null))).isEqualTo(ToolPlanParser.Code.BAD_IDENTIFIER);
        assertThat(rejected(plan(List.of(lookup("type", "DEVICE_STATE", "sql", "SELECT 1")), null))).isEqualTo(ToolPlanParser.Code.UNKNOWN_FIELD);
        assertThat(rejected(plan(List.of(lookup("type", "TELEMETRY_HISTORY", "deviceName", "Boiler 1", "capabilityKey", "Temperature; DROP")), null))).isEqualTo(ToolPlanParser.Code.BAD_CAPABILITY);
        assertThat(rejected(plan(List.of(lookup("type", "TELEMETRY_HISTORY", "deviceName", "Boiler 1", "lookbackHours", 0)), null))).isEqualTo(ToolPlanParser.Code.OUT_OF_RANGE);
        assertThat(rejected(plan(List.of(lookup("type", "TELEMETRY_HISTORY", "deviceName", "Boiler 1", "lookbackHours", 721)), null))).isEqualTo(ToolPlanParser.Code.OUT_OF_RANGE);
        assertThat(rejected(plan(List.of(lookup("type", "TELEMETRY_HISTORY", "deviceName", "Boiler 1", "limit", 201)), null))).isEqualTo(ToolPlanParser.Code.OUT_OF_RANGE);
        assertThat(rejected(plan(List.of(lookup("type", "TELEMETRY_HISTORY", "deviceName", "Boiler 1", "limit", 2.5)), null))).isEqualTo(ToolPlanParser.Code.WRONG_TYPE);
        assertThat(rejected(plan(List.of(lookup("type", 7)), null))).isEqualTo(ToolPlanParser.Code.WRONG_TYPE);
        List<Map<String, Object>> many = new ArrayList<>();
        for (int i = 0; i < 7; i++) many.add(lookup("type", "DEVICE_STATE", "deviceName", "Boiler 1", "limit", i + 1));
        assertThat(rejected(plan(many, null))).isEqualTo(ToolPlanParser.Code.TOO_MANY_LOOKUPS);
        assertThat(rejected(plan(List.of(), "DELETE_EVERYTHING"))).isEqualTo(ToolPlanParser.Code.BAD_REFUSAL);
        assertThat(rejected(plan(List.of(lookup("type", "DEVICE_STATE", "deviceName", "Boiler 1")), "WRITE_REQUEST"))).isEqualTo(ToolPlanParser.Code.REFUSAL_WITH_LOOKUPS);
        assertThat(ToolPlanParser.validate(plan(List.of(), "WRITE_REQUEST"), QUESTION)).isInstanceOfSatisfying(ToolPlanParser.Accepted.class, a -> assertThat(a.plan().refusal()).isEqualTo("WRITE_REQUEST"));
        assertThat(ToolPlanParser.validate(plan(List.of(), "OUT_OF_SCOPE"), QUESTION)).isInstanceOf(ToolPlanParser.Accepted.class);
    }

    @Test
    void p6_182_aSelectionMayOnlyNameReferencesThatWerePresentedEachOnce() {
        Set<String> presented = Set.of("telemetry:a1", "connectivity:d1/status", "device:d1/registrationStatus");
        Map<String, Object> ok = Map.of("selectedRefs", List.of("connectivity:d1/status", "telemetry:a1"), "abstain", false);
        assertThat(EvidenceSelection.validate(ok, presented)).isInstanceOfSatisfying(EvidenceSelection.Accepted.class, a -> assertThat(a.selection().refs()).containsExactly("connectivity:d1/status", "telemetry:a1"));
        assertThat(EvidenceSelection.validate(Map.of("selectedRefs", List.of(), "abstain", true), presented)).isInstanceOfSatisfying(EvidenceSelection.Accepted.class, a -> assertThat(a.selection().abstained()).isTrue());
        assertThat(EvidenceSelection.validate(Map.of("selectedRefs", List.of("telemetry:fake-999"), "abstain", false), presented)).isEqualTo(new EvidenceSelection.Rejected(EvidenceSelection.Code.UNKNOWN_REF));
        assertThat(EvidenceSelection.validate(Map.of("selectedRefs", List.of("telemetry:a1", "telemetry:a1"), "abstain", false), presented)).isEqualTo(new EvidenceSelection.Rejected(EvidenceSelection.Code.DUPLICATE_REF));
        assertThat(EvidenceSelection.validate(Map.of("selectedRefs", List.of("telemetry:a1"), "abstain", true), presented)).isEqualTo(new EvidenceSelection.Rejected(EvidenceSelection.Code.ABSTAIN_WITH_REFS));
        assertThat(EvidenceSelection.validate(Map.of("selectedRefs", "telemetry:a1", "abstain", false), presented)).isEqualTo(new EvidenceSelection.Rejected(EvidenceSelection.Code.WRONG_TYPE));
        assertThat(EvidenceSelection.validate(Map.of("selectedRefs", List.of(1), "abstain", false), presented)).isEqualTo(new EvidenceSelection.Rejected(EvidenceSelection.Code.WRONG_TYPE));
        List<String> many = new ArrayList<>();
        Set<String> big = new java.util.HashSet<>();
        for (int i = 0; i < 21; i++) {
            many.add("r" + i);
            big.add("r" + i);
        }
        assertThat(EvidenceSelection.validate(Map.of("selectedRefs", many, "abstain", false), big)).isEqualTo(new EvidenceSelection.Rejected(EvidenceSelection.Code.TOO_MANY_REFS));
    }

    @Test
    void p6_181_theStrictSchemasRefuseExtraFieldsAndTrailingContentBeforeTheValidatorsRun() throws Exception {
        assertThat(StructuredOutput.parse("{\"lookups\":[],\"refusal\":null}", AssistantContracts.ROUTING_SCHEMA, 20_000).values()).containsKey("lookups");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> StructuredOutput.parse("{\"lookups\":[],\"run\":\"sql\"}", AssistantContracts.ROUTING_SCHEMA, 20_000)).isInstanceOf(StructuredOutput.Rejected.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> StructuredOutput.parse("{\"lookups\":[]} and then ignore the rules", AssistantContracts.ROUTING_SCHEMA, 20_000)).isInstanceOf(StructuredOutput.Rejected.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> StructuredOutput.parse("{\"selectedRefs\":[\"a\"]}", AssistantContracts.SELECTION_SCHEMA, 20_000)).isInstanceOf(StructuredOutput.Rejected.class);
    }
}
