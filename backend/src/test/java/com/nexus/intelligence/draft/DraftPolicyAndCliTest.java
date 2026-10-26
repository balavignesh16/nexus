package com.nexus.intelligence.draft;

import com.nexus.intelligence.draft.store.DraftStore;
import com.nexus.rule.approval.DraftApprovalCli;
import com.nexus.rule.domain.Rule;
import com.nexus.rule.domain.RuleAction;
import com.nexus.rule.domain.RuleCondition;
import com.nexus.rule.domain.RuleField;
import com.nexus.rule.domain.RuleOperator;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The deterministic policy on plain values, the retention setting, and the approval command's argument and credential handling without a database. */
class DraftPolicyAndCliTest {
    private static final UUID DEVICE = UUID.randomUUID();

    private static Rule rule(int priority, Integer cooldown, List<RuleAction> actions) {
        return new Rule(UUID.randomUUID(), "AI rule", false, priority, List.of(new RuleCondition(RuleField.DEVICE_ID, RuleOperator.EQ, DEVICE.toString())), actions, Map.of(), cooldown, null, null);
    }

    private static RuleAction command(UUID device) {
        return new RuleAction("SEND_COMMAND", Map.of("deviceId", device.toString(), "capability", "LED", "command", "SET_STATE", "value", true));
    }

    private static List<String> codes(Rule r, UUID... cited) {
        return DraftPolicy.check(r, Set.of(cited)).stream().map(m -> m.get("code").toString()).toList();
    }

    @Test
    void p6_042_thePolicyAcceptsAnAlertAndACitedCommandAndRefusesTheRest() {
        assertThat(codes(rule(5, 60, List.of(new RuleAction("CREATE_ALERT", Map.of())))) ).isEmpty();
        assertThat(codes(rule(5, 60, List.of(command(DEVICE))), DEVICE)).isEmpty();
        assertThat(codes(rule(5, 60, List.of(command(DEVICE))))).containsExactly("COMMAND_TARGET_NOT_CITED");
        assertThat(codes(rule(5, 60, List.of(new RuleAction("SEND_COMMAND", Map.of("capability", "LED")))))).containsExactly("COMMAND_TARGET_MISSING");
        assertThat(codes(rule(5, 60, List.of(new RuleAction("WEBHOOK", Map.of("url", "http://x")))))).containsExactly("EXTERNAL_EGRESS_FORBIDDEN");
        assertThat(codes(rule(5, 60, List.of(new RuleAction("RUN_SHELL", Map.of()))))).containsExactly("ACTION_TYPE_FORBIDDEN");
        assertThat(codes(rule(5, null, List.of(new RuleAction("CREATE_ALERT", Map.of()))))).containsExactly("COOLDOWN_RANGE");
        assertThat(codes(rule(5, 29, List.of(new RuleAction("CREATE_ALERT", Map.of()))))).containsExactly("COOLDOWN_RANGE");
        assertThat(codes(rule(5, 86_401, List.of(new RuleAction("CREATE_ALERT", Map.of()))))).containsExactly("COOLDOWN_RANGE");
        assertThat(codes(rule(-1, 60, List.of(new RuleAction("CREATE_ALERT", Map.of()))))).containsExactly("PRIORITY_RANGE");
        assertThat(codes(rule(5, 60, List.of(new RuleAction("CREATE_ALERT", Map.of()), new RuleAction("CREATE_ALERT", Map.of()), new RuleAction("CREATE_ALERT", Map.of()), new RuleAction("CREATE_ALERT", Map.of()))))).containsExactly("ACTION_COUNT");
        assertThat(codes(rule(5, 60, List.of()))).containsExactly("ACTION_COUNT");
    }

    @Test
    void p6_054_theRetentionWindowIsValidatedAndDefaultsToThirtyDays() {
        assertThat(new DraftStore(null, new com.fasterxml.jackson.databind.ObjectMapper(), 30).retentionDays()).isEqualTo(30);
        assertThatThrownBy(() -> new DraftStore(null, new com.fasterxml.jackson.databind.ObjectMapper(), 0)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new DraftStore(null, new com.fasterxml.jackson.databind.ObjectMapper(), 366)).isInstanceOf(IllegalStateException.class);
    }

    private record Result(int code, String out, String err) {}

    private static Result cli(Map<String, String> env, String... args) {
        ByteArrayOutputStream o = new ByteArrayOutputStream(), e = new ByteArrayOutputStream();
        int code = DraftApprovalCli.run(args, env, new PrintStream(o), new PrintStream(e), "someone");
        return new Result(code, o.toString(), e.toString());
    }

    @Test
    void p6_046_theApprovalCommandRefusesBadUsageAndMissingCredentialsBeforeAnyConnection() {
        assertThat(cli(Map.of()).code()).isEqualTo(2);
        assertThat(cli(Map.of(), "frobnicate").code()).isEqualTo(2);
        Result noCredentials = cli(Map.of(), "approve", "--draft", UUID.randomUUID().toString(), "--hash", "a".repeat(64), "--reason", "ok");
        assertThat(noCredentials.code()).isEqualTo(1);
        assertThat(noCredentials.err()).contains("NEXUS_APPROVER_DB_URL");
        // credentials are never arguments: a password option is a usage error
        Map<String, String> env = Map.of("NEXUS_APPROVER_DB_URL", "jdbc:postgresql://127.0.0.1:1/x", "NEXUS_APPROVER_DB_USER", "u", "NEXUS_APPROVER_DB_PASSWORD", "p");
        assertThat(cli(env, "approve", "--draft", UUID.randomUUID().toString(), "--hash", "a".repeat(64), "--reason", "ok", "--password", "p").code()).isEqualTo(2);
        assertThat(cli(env, "approve", "--draft", "not-a-uuid", "--hash", "a".repeat(64), "--reason", "ok").code()).isEqualTo(2);
        assertThat(cli(env, "approve", "--draft", UUID.randomUUID().toString(), "--hash", "short", "--reason", "ok").code()).isEqualTo(2);
        assertThat(cli(env, "approve", "--draft", UUID.randomUUID().toString(), "--hash", "a".repeat(64), "--reason", " ").code()).isEqualTo(2);
        assertThat(cli(env, "approve", "--draft", UUID.randomUUID().toString(), "--hash", "a".repeat(64), "--reason", "x".repeat(501)).code()).isEqualTo(2);
        // an unreachable database is a fixed refusal that does not echo the credentials
        Result unreachable = cli(env, "list");
        assertThat(unreachable.code()).isEqualTo(1);
        assertThat(unreachable.err() + unreachable.out()).doesNotContain("jdbc:postgresql").doesNotContain("password");
    }
}
