package com.nexus.intelligence.assistant;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P6-185 and P6-122: the assistant has no tool that changes anything. Its code reaches the platform only through the retrieval service and the trend analysis: no
 * command, rule lifecycle, approval, draft, SQL or repository type is imported, there is no write call, and the only route that exists is the question route. It is
 * off unless explicitly enabled.
 */
class AssistantBoundaryTest {
    private static final Path DIR = Path.of("src/main/java/com/nexus/intelligence/assistant");
    private static final Path CONTROLLER = Path.of("src/main/java/com/nexus/intelligence/api/AssistantController.java");
    private static final List<Pattern> FORBIDDEN = List.of(
            Pattern.compile("^import\\s+(static\\s+)?com\\.nexus\\.action\\b", Pattern.MULTILINE),
            Pattern.compile("^import\\s+(static\\s+)?com\\.nexus\\.adapter\\b", Pattern.MULTILINE),
            Pattern.compile("^import\\s+(static\\s+)?com\\.nexus\\.device\\b", Pattern.MULTILINE),
            Pattern.compile("^import\\s+(static\\s+)?com\\.nexus\\.rule\\.(application|approval|persistence|engine|plan|trigger)\\b", Pattern.MULTILINE),
            Pattern.compile("^import\\s+(static\\s+)?com\\.nexus\\.intelligence\\.draft\\b", Pattern.MULTILINE),
            Pattern.compile("^import\\s+(static\\s+)?com\\.nexus\\.twin\\b", Pattern.MULTILINE),
            Pattern.compile("^import\\s+(static\\s+)?com\\.nexus\\.pipeline\\b", Pattern.MULTILINE),
            Pattern.compile("^import\\s+(static\\s+)?org\\.springframework\\.(jdbc|data|orm)\\b", Pattern.MULTILINE),
            Pattern.compile("^import\\s+(static\\s+)?jakarta\\.persistence\\b", Pattern.MULTILINE),
            Pattern.compile("^import\\s+(static\\s+)?com\\.nexus\\.intelligence\\.trend\\.HardLimitDrafts\\b", Pattern.MULTILINE));
    private static final Pattern WRITE_CALL = Pattern.compile("\\.(save|saveAll|delete|deleteAll|update|batchUpdate|execute|executeWithoutResult)\\s*\\(");
    private static final Pattern WRITE_MAPPING = Pattern.compile("@(Put|Patch|Delete)Mapping|RequestMethod\\.(PUT|PATCH|DELETE)");

    static List<String> violations(String source) {
        List<String> out = new ArrayList<>();
        for (Pattern p : FORBIDDEN) if (p.matcher(source).find()) out.add(p.pattern());
        return out;
    }

    private static List<Path> sources() throws IOException {
        List<Path> out = new ArrayList<>();
        try (Stream<Path> s = Files.walk(DIR)) {
            s.filter(p -> p.toString().endsWith(".java")).forEach(out::add);
        }
        out.add(CONTROLLER);
        return out;
    }

    @Test
    void theRulesDetectWhatTheyExistToDetect() {
        assertThat(violations("import com.nexus.action.application.CommandAcceptanceService;")).isNotEmpty();
        assertThat(violations("import com.nexus.rule.application.RuleLifecycleService;")).isNotEmpty();
        assertThat(violations("import com.nexus.intelligence.draft.DraftService;")).isNotEmpty();
        assertThat(violations("import org.springframework.jdbc.core.JdbcTemplate;")).isNotEmpty();
        assertThat(violations("import com.nexus.intelligence.trend.HardLimitDrafts;")).isNotEmpty();
        assertThat(violations("import com.nexus.intelligence.retrieval.EvidenceRetrievalService;")).isEmpty();
        assertThat(WRITE_CALL.matcher("jdbc.update(x)").find()).isTrue();
    }

    @Test
    void p6_185_theAssistantCodeImportsNoCommandRuleApprovalDraftSqlOrRepositoryTypeAndHasNoWriteCall() throws IOException {
        List<Path> sources = sources();
        assertThat(sources).hasSizeGreaterThanOrEqualTo(8);
        List<String> found = new ArrayList<>();
        for (Path p : sources) {
            String text = Files.readString(p);
            for (String v : violations(text)) found.add(p + " imports " + v);
            String code = text.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//.*", "");
            if (WRITE_CALL.matcher(code).find()) found.add(p + " calls a write method");
            if (WRITE_MAPPING.matcher(code).find()) found.add(p + " maps a write route");
        }
        assertThat(found).isEmpty();
    }

    @Test
    void p6_185_theOnlyRouteIsTheQuestionRouteAndItWritesNoState() throws IOException {
        String controller = Files.readString(CONTROLLER);
        assertThat(Pattern.compile("@PostMapping").matcher(controller).results().count()).isEqualTo(1);
        assertThat(controller).contains("@PostMapping(\"/ask\")").doesNotContain("@GetMapping").doesNotContain("@PutMapping").doesNotContain("@DeleteMapping");
        String service = Files.readString(DIR.resolve("AssistantService.java"));
        assertThat(service).doesNotContain("proposeTemplate").doesNotContain(".propose(").doesNotContain(".approve(").doesNotContain(".activate(").doesNotContain(".transition(");
    }

    @Test
    void p6_122_theServiceAndTheRouteExistOnlyWhenTheAssistantIsEnabled() {
        for (Class<?> type : List.of(AssistantService.class, com.nexus.intelligence.api.AssistantController.class)) {
            ConditionalOnProperty condition = type.getAnnotation(ConditionalOnProperty.class);
            assertThat(condition).as(type.getSimpleName()).isNotNull();
            assertThat(condition.prefix()).isEqualTo("nexus.assistant");
            assertThat(condition.name()).containsExactly("enabled");
            assertThat(condition.havingValue()).isEqualTo("true");
            assertThat(condition.matchIfMissing()).isFalse();
        }
        new ApplicationContextRunner().withUserConfiguration(AssistantService.class).run(context -> assertThat(context).doesNotHaveBean(AssistantService.class));
    }
}
