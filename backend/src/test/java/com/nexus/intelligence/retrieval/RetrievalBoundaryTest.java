package com.nexus.intelligence.retrieval;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P6-121 and P6-122: the retrieval layer imports nothing that can execute a command, activate or approve a rule, create a draft, publish or write the Twin; its SQL
 * is read-only; no write route or write method exists; and the assistant is off unless explicitly enabled. Source scans are exercised on synthetic sources too, so a
 * rule that stopped matching would fail here.
 */
class RetrievalBoundaryTest {
    private static final Path RETRIEVAL = Path.of("src/main/java/com/nexus/intelligence/retrieval");
    private static final Path CONTROLLER = Path.of("src/main/java/com/nexus/intelligence/api/AssistantEvidenceController.java");

    private static final List<Pattern> FORBIDDEN_IMPORTS = List.of(
            Pattern.compile("^import\\s+(static\\s+)?com\\.nexus\\.action\\b", Pattern.MULTILINE),
            Pattern.compile("^import\\s+(static\\s+)?com\\.nexus\\.adapter\\b", Pattern.MULTILINE),
            Pattern.compile("^import\\s+(static\\s+)?com\\.nexus\\.device\\.command\\b", Pattern.MULTILINE),
            Pattern.compile("^import\\s+(static\\s+)?com\\.nexus\\.rule\\.application\\b", Pattern.MULTILINE),
            Pattern.compile("^import\\s+(static\\s+)?com\\.nexus\\.rule\\.approval\\b", Pattern.MULTILINE),
            Pattern.compile("^import\\s+(static\\s+)?com\\.nexus\\.intelligence\\.draft\\b", Pattern.MULTILINE),
            Pattern.compile("^import\\s+(static\\s+)?com\\.nexus\\.twin\\.application\\b", Pattern.MULTILINE),
            Pattern.compile("^import\\s+(static\\s+)?com\\.nexus\\.pipeline\\b", Pattern.MULTILINE),
            Pattern.compile("^import\\s+(static\\s+)?com\\.nexus\\.infrastructure\\.mqtt\\b", Pattern.MULTILINE),
            Pattern.compile("^import\\s+(static\\s+)?org\\.eclipse\\.paho\\b", Pattern.MULTILINE));

    /** A SQL string literal that starts with a write verb (set_config is the only statement allowed to change a session setting). */
    private static final Pattern SQL_WRITE = Pattern.compile("\"\\s*(INSERT|UPDATE|DELETE|MERGE|TRUNCATE|ALTER|DROP|CREATE|GRANT|REVOKE|LOCK|COPY|CALL|DO)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern WRITE_CALL = Pattern.compile("\\.(save|saveAll|delete|deleteAll|update|batchUpdate|execute)\\s*\\(");
    private static final Pattern WRITE_MAPPING = Pattern.compile("@(Post|Put|Patch|Delete)Mapping|RequestMethod\\.(POST|PUT|PATCH|DELETE)");

    static List<String> importViolations(String source) {
        List<String> found = new ArrayList<>();
        for (Pattern p : FORBIDDEN_IMPORTS) if (p.matcher(source).find()) found.add(p.pattern());
        return found;
    }

    static boolean writesSql(String source) {
        Matcher m = SQL_WRITE.matcher(source);
        return m.find();
    }

    private static List<Path> sources() throws IOException {
        List<Path> out = new ArrayList<>();
        try (Stream<Path> s = Files.walk(RETRIEVAL)) {
            s.filter(p -> p.toString().endsWith(".java")).forEach(out::add);
        }
        out.add(CONTROLLER);
        return out;
    }

    @Test
    void theRulesDetectWhatTheyExistToDetect() {
        assertThat(importViolations("import com.nexus.action.application.CommandAcceptanceService;")).isNotEmpty();
        assertThat(importViolations("import com.nexus.rule.application.RuleLifecycleService;")).isNotEmpty();
        assertThat(importViolations("import com.nexus.intelligence.draft.DraftService;")).isNotEmpty();
        assertThat(importViolations("import com.nexus.rule.approval.DraftApprovalCli;")).isNotEmpty();
        assertThat(importViolations("import com.nexus.rule.persistence.RuleVersionStore;")).isEmpty();
        assertThat(writesSql("jdbc.update(\"UPDATE rules SET enabled = true\")")).isTrue();
        assertThat(writesSql("jdbc.query(\"SELECT id FROM devices\")")).isFalse();
        assertThat(WRITE_MAPPING.matcher("@PostMapping(\"/x\")").find()).isTrue();
        assertThat(WRITE_CALL.matcher("jdbc.update(sql)").find()).isTrue();
    }

    @Test
    void p6_121_theRetrievalCodeImportsNoExecutionActivationApprovalDraftOrPublicationType() throws IOException {
        List<Path> sources = sources();
        assertThat(sources).hasSizeGreaterThanOrEqualTo(10);
        List<String> violations = new ArrayList<>();
        for (Path p : sources) for (String v : importViolations(Files.readString(p))) violations.add(p + " -> " + v);
        assertThat(violations).isEmpty();
    }

    @Test
    void p6_121_theRetrievalCodeHasNoWriteSqlNoWriteCallsAndNoWriteRoute() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path p : sources()) {
            String code = Files.readString(p).replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//.*", "");
            if (writesSql(code)) violations.add(p + " writes SQL");
            if (WRITE_CALL.matcher(code).find()) violations.add(p + " calls a write method");
            if (WRITE_MAPPING.matcher(code).find()) violations.add(p + " maps a write route");
        }
        assertThat(violations).isEmpty();
        String service = Files.readString(RETRIEVAL.resolve("EvidenceRetrievalService.java"));
        assertThat(service).contains("setReadOnly(true)").contains("set_config('statement_timeout'");
    }

    @Test
    void p6_121_everyQueryIsAFixedStatementWithBoundParametersNotBuiltFromRequestText() throws IOException {
        String service = Files.readString(RETRIEVAL.resolve("EvidenceRetrievalService.java"));
        // the only string concatenation allowed next to SQL is a constant limit text; request values are never concatenated into a statement
        Matcher m = Pattern.compile("(jdbc\\.(query|queryForList|queryForObject)\\()([^;]*?)\\+\\s*(r|request)\\.[a-zA-Z]+\\(\\)", Pattern.DOTALL).matcher(service);
        assertThat(m.find()).as("a request value concatenated into a statement").isFalse();
    }

    @Test
    void p6_122_theServiceAndTheEndpointExistOnlyWhenTheAssistantIsEnabledAndTheSettingsDefaultToOff() {
        for (Class<?> type : List.of(EvidenceRetrievalService.class, com.nexus.intelligence.api.AssistantEvidenceController.class)) {
            ConditionalOnProperty condition = type.getAnnotation(ConditionalOnProperty.class);
            assertThat(condition).as(type.getSimpleName()).isNotNull();
            assertThat(condition.prefix()).isEqualTo("nexus.assistant");
            assertThat(condition.name()).containsExactly("enabled");
            assertThat(condition.havingValue()).isEqualTo("true");
            assertThat(condition.matchIfMissing()).isFalse();
        }
        assertThat(RetrievalSettings.defaults().enabled()).isFalse();
        new ApplicationContextRunner().withUserConfiguration(RetrievalConfiguration.class, EvidenceRetrievalService.class).run(context -> {
            assertThat(context).hasSingleBean(RetrievalSettings.class);
            assertThat(context.getBean(RetrievalSettings.class).enabled()).isFalse();
            assertThat(context).doesNotHaveBean(EvidenceRetrievalService.class);
        });
        new ApplicationContextRunner().withUserConfiguration(RetrievalConfiguration.class).withPropertyValues("nexus.assistant.enabled=true")
                .run(context -> assertThat(context.getBean(RetrievalSettings.class).enabled()).isTrue());
    }

    @Test
    void p6_119_outOfRangeSettingsStopStartup() {
        assertThat(org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () -> new RetrievalSettings(true, 0, 200, 168, 65_536, 2_000, 4, 8_000, 900, 300, 200)).getMessage()).contains("max-lookups-per-question");
        assertThat(org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () -> new RetrievalSettings(true, 6, 200, 168, 65_536, 2_000, 99, 8_000, 900, 300, 200)).getMessage()).contains("max-concurrent");
        assertThat(org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () -> new RetrievalSettings(true, 6, 5_000, 168, 65_536, 2_000, 4, 8_000, 900, 300, 200)).getMessage()).contains("max-rows");
    }
}
