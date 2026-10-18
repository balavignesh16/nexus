package com.nexus.intelligence;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P6-004 / P6-016: the AI-facing packages (com.nexus.rule.ai and com.nexus.intelligence) cannot reach command execution, adapters, MQTT, Twin mutation or
 * rule activation, cannot write through JDBC/JPA outside an explicit allow-list, and nothing outside them depends on them except two named files.
 * The rules are source scans; they are themselves exercised on synthetic sources so a rule that stopped matching would fail here.
 */
class IntelligenceArchitectureTest {
    private static final Path MAIN = Path.of("src/main/java");
    private static final List<String> AI_DIRS = List.of("com/nexus/rule/ai", "com/nexus/intelligence");

    private static final List<Pattern> FORBIDDEN_IMPORTS = List.of(
            Pattern.compile("^import\\s+(static\\s+)?com\\.nexus\\.action\\.(application|outbox|infrastructure)\\b", Pattern.MULTILINE),
            Pattern.compile("^import\\s+(static\\s+)?com\\.nexus\\.adapter\\b", Pattern.MULTILINE),
            Pattern.compile("^import\\s+(static\\s+)?com\\.nexus\\.infrastructure\\.mqtt\\b", Pattern.MULTILINE),
            Pattern.compile("^import\\s+(static\\s+)?com\\.nexus\\.device\\.command\\b", Pattern.MULTILINE),
            Pattern.compile("^import\\s+(static\\s+)?com\\.nexus\\.rule\\.(application\\.RuleLifecycleService|engine\\.(RuleMatcher|executor)\\b)", Pattern.MULTILINE),
            Pattern.compile("^import\\s+(static\\s+)?com\\.nexus\\.twin\\.application\\b", Pattern.MULTILINE),
            Pattern.compile("^import\\s+(static\\s+)?com\\.nexus\\.pipeline\\b", Pattern.MULTILINE),
            Pattern.compile("^import\\s+(static\\s+)?com\\.nexus\\.rule\\.approval\\b", Pattern.MULTILINE),
            Pattern.compile("^import\\s+(static\\s+)?org\\.eclipse\\.paho\\b", Pattern.MULTILINE),
            Pattern.compile("^import\\s+(static\\s+)?org\\.springframework\\.(integration|amqp|kafka)\\b", Pattern.MULTILINE));

    private static final Pattern WRITE_CALL = Pattern.compile("\\.(save|saveAll|delete|deleteAll|update|batchUpdate|execute)\\s*\\(");

    /** Packages that may hold writes (P6.3 draft store). */
    private static final List<String> WRITE_ALLOWED = List.of("com/nexus/intelligence/draft/store");

    private static List<Path> aiSources() throws IOException {
        List<Path> out = new ArrayList<>();
        for (String dir : AI_DIRS) {
            Path root = MAIN.resolve(dir);
            if (!Files.isDirectory(root)) continue;
            try (Stream<Path> s = Files.walk(root)) {
                s.filter(p -> p.toString().endsWith(".java")).forEach(out::add);
            }
        }
        return out;
    }

    static List<String> importViolations(String source) {
        List<String> found = new ArrayList<>();
        for (Pattern p : FORBIDDEN_IMPORTS) if (p.matcher(source).find()) found.add(p.pattern());
        return found;
    }

    static boolean writesThroughStore(String source) {
        return WRITE_CALL.matcher(source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//.*", "")).find();
    }

    @Test
    void p6_004_theAiPackagesImportNoExecutionAdapterMqttOrActivationType() throws IOException {
        List<Path> sources = aiSources();
        assertThat(sources).hasSizeGreaterThan(40);
        List<String> violations = new ArrayList<>();
        for (Path p : sources) for (String v : importViolations(Files.readString(p))) violations.add(p + " -> " + v);
        assertThat(violations).isEmpty();
    }

    @Test
    void p6_005_theAiPackagesContainNoWriteCallsOutsideTheAllowList() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path p : aiSources()) {
            String unix = p.toString().replace('\\', '/');
            if (WRITE_ALLOWED.stream().anyMatch(unix::contains)) continue;
            if (writesThroughStore(Files.readString(p))) violations.add(unix);
        }
        assertThat(violations).isEmpty();
    }

    @Test
    void p6_006_nothingOutsideTheAiPackagesDependsOnThemExceptTwoNamedFiles() throws IOException {
        Pattern dependsOnAi = Pattern.compile("^import\\s+(static\\s+)?com\\.nexus\\.(rule\\.ai|intelligence)\\b", Pattern.MULTILINE);
        List<String> importers = new ArrayList<>();
        try (Stream<Path> s = Files.walk(MAIN)) {
            for (Path p : (Iterable<Path>) s.filter(f -> f.toString().endsWith(".java"))::iterator) {
                String unix = p.toString().replace('\\', '/');
                if (AI_DIRS.stream().anyMatch(unix::contains)) continue;
                if (dependsOnAi.matcher(Files.readString(p)).find()) importers.add(unix.substring(unix.lastIndexOf('/') + 1));
            }
        }
        assertThat(importers).containsExactlyInAnyOrder("CommandLifecycleStatements.java", "GlobalExceptionHandler.java");
    }

    @Test
    void p6_004_theRulesCatchWhatTheyAreMeantToCatch() {
        assertThat(importViolations("import com.nexus.action.application.CommandAcceptanceService;")).isNotEmpty();
        assertThat(importViolations("import com.nexus.action.outbox.OutboxRepository;")).isNotEmpty();
        assertThat(importViolations("import com.nexus.adapter.mqtt.MqttJsonAdapter;")).isNotEmpty();
        assertThat(importViolations("import com.nexus.infrastructure.mqtt.MqttPublisher;")).isNotEmpty();
        assertThat(importViolations("import com.nexus.rule.application.RuleLifecycleService;")).isNotEmpty();
        assertThat(importViolations("import com.nexus.rule.engine.executor.CommandExecutor;")).isNotEmpty();
        assertThat(importViolations("import com.nexus.twin.application.DigitalTwinService;")).isNotEmpty();
        assertThat(importViolations("import com.nexus.pipeline.TelemetryPipeline;")).isNotEmpty();
        assertThat(importViolations("import com.nexus.rule.approval.DraftApprovalCli;")).isNotEmpty();
        assertThat(importViolations("import java.util.List;\nimport com.nexus.intelligence.evidence.EvidenceBundle;")).isEmpty();
        assertThat(writesThroughStore("repo.save(x);")).isTrue();
        assertThat(writesThroughStore("jdbc.update(\"insert\");")).isTrue();
        assertThat(writesThroughStore("// repo.save(x)\n/* jdbc.execute(1) */ int a = 1;")).isFalse();
        assertThat(writesThroughStore("jdbc.queryForObject(\"select 1\", Integer.class);")).isFalse();
    }

    private static final Pattern DECISION_ROUTE = Pattern.compile("(?i)(Post|Put|Patch|Delete)Mapping\\s*\\([^)]*(approv|reject|decision|decide|activate)");

    @Test
    void p6_046_onlyTheLocalApprovalCommandNamesTheDecisionFunctionAndNoRouteMentionsADecision() throws IOException {
        List<String> namingDecision = new ArrayList<>();
        List<String> routes = new ArrayList<>();
        try (Stream<Path> s = Files.walk(MAIN)) {
            for (Path p : (Iterable<Path>) s.filter(f -> f.toString().endsWith(".java"))::iterator) {
                String unix = p.toString().replace('\\', '/');
                String src = Files.readString(p);
                if (src.contains("ai_draft_decide") || src.contains("ai_rule_draft_decisions")) namingDecision.add(unix.substring(unix.lastIndexOf('/') + 1));
                boolean controller = src.contains("@RestController") || src.contains("@Controller");
                if (controller && unix.contains("com/nexus/intelligence") && DECISION_ROUTE.matcher(src).find()) routes.add(unix);
            }
        }
        assertThat(namingDecision).containsExactly("DraftApprovalCli.java");
        assertThat(routes).isEmpty();
        assertThat(DECISION_ROUTE.matcher("@PostMapping(\"/{id}/approve\")").find()).isTrue(); // the rule fires on a violating source
        assertThat(DECISION_ROUTE.matcher("@PostMapping(\"/drafts\")").find()).isFalse();
    }

    @Test
    void p6_065_p6_069_detectionAndRecommendationCannotReachDraftsApprovalOrAnyModelExceptThroughTheNarrator() throws IOException {
        List<String> violations = new ArrayList<>();
        int scanned = 0;
        for (String dir : List.of("com/nexus/intelligence/anomaly", "com/nexus/intelligence/recommend")) {
            try (Stream<Path> s = Files.walk(MAIN.resolve(dir))) {
                for (Path p : (Iterable<Path>) s.filter(f -> f.toString().endsWith(".java"))::iterator) {
                    scanned++;
                    String src = Files.readString(p);
                    String name = p.getFileName().toString();
                    if (src.contains("import com.nexus.intelligence.draft")) violations.add(name + " imports the draft code");
                    if (src.contains("com.nexus.rule.approval")) violations.add(name + " names the approval code");
                    if (src.contains("ModelGateway") && !name.equals("RecommendationNarrator.java")) violations.add(name + " reaches the model gateway");
                    if (src.contains("import com.nexus.intelligence.model.") && !name.equals("RecommendationNarrator.java")) violations.add(name + " imports the model layer");
                    if (src.contains("ModelProvider") || src.contains("RestClient")) violations.add(name + " reaches a provider or an HTTP client");
                }
            }
        }
        assertThat(scanned).isGreaterThan(10);
        assertThat(violations).isEmpty();
    }

    @Test
    void p6_071_detectionAndRecommendationHaveNoBackgroundTaskNoEventListenerAndNothingOnTheIngestionPath() throws IOException {
        List<String> found = new ArrayList<>();
        Pattern background = Pattern.compile("@(Scheduled|Async|EventListener|TransactionalEventListener|PostConstruct|EnableScheduling)\\b");
        for (String dir : List.of("com/nexus/intelligence/anomaly", "com/nexus/intelligence/recommend", "com/nexus/intelligence/api")) {
            try (Stream<Path> s = Files.walk(MAIN.resolve(dir))) {
                for (Path p : (Iterable<Path>) s.filter(f -> f.toString().endsWith(".java"))::iterator) if (background.matcher(Files.readString(p)).find()) found.add(p.getFileName().toString());
            }
        }
        assertThat(found).as("Layer 3 runs only when asked: no scheduler, async executor or event listener").isEmpty();
        assertThat(background.matcher("@Scheduled(fixedDelay = 1)").find()).isTrue();
    }
}
