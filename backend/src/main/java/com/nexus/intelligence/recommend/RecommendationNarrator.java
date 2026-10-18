package com.nexus.intelligence.recommend;

import com.nexus.intelligence.model.DataBlock;
import com.nexus.intelligence.model.ModelGateway;
import com.nexus.intelligence.model.ModelOutcome;
import com.nexus.intelligence.model.ModelPurpose;
import com.nexus.intelligence.model.ModelRequest;
import com.nexus.intelligence.model.ProviderIdentity;
import com.nexus.intelligence.model.StructuredOutput;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * OPTIONAL model help for reading a list of recommendations: it may put them in a suggested reading order and write a short summary. It can introduce
 * no recommendation and change none. The model is reached only through the {@link ModelGateway} (enabled gate, device-evidence consent, bounded context,
 * strict JSON), and its answer is accepted only if the order is exactly a permutation of the recommendations it was shown, every number in the summary
 * appears in the facts it was shown, and the summary makes no probability, certainty, fault or failure-prediction claim. Anything else is rejected and the
 * deterministic list stands alone. Without a model the deterministic engine is complete.
 */
@Service
public class RecommendationNarrator {
    public record Narration(String status, String reason, List<String> rankedIds, String summary, ProviderIdentity generatedBy) {
        static Narration unavailable(String reason, ProviderIdentity id) {
            return new Narration("UNAVAILABLE", reason, List.of(), null, id);
        }
    }

    static final int MAX_SUMMARY = 600;
    private static final Pattern NUMBER = Pattern.compile("\\d+(?:\\.\\d+)?");
    private static final Pattern FORBIDDEN = Pattern.compile(
            "(?i)(probab|confiden|likel(y|ihood)|percent|%|certain|guarantee|confirmed|definitely|will (soon )?(fail|break|stop)|going to (fail|break)|is (faulty|broken|defective|dead|failing)|has failed|hardware (fault|failure))");
    private static final StructuredOutput.Schema SCHEMA = StructuredOutput.Schema.of(
            Map.of("ranking", StructuredOutput.FieldType.STRING_LIST, "summary", StructuredOutput.FieldType.STRING), Set.of("ranking", "summary"));

    private final ModelGateway gateway;

    public RecommendationNarrator(ModelGateway gateway) {
        this.gateway = gateway;
    }

    public Narration narrate(List<Recommendation> recommendations) {
        if (recommendations.isEmpty()) return new Narration("NOT_NEEDED", "NO_RECOMMENDATIONS", List.of(), null, ProviderIdentity.NONE);
        Map<String, String> shortIds = new LinkedHashMap<>();
        StringBuilder facts = new StringBuilder();
        for (Recommendation r : recommendations) {
            String sid = "R" + (shortIds.size() + 1);
            shortIds.put(sid, r.id());
            facts.append(sid).append(' ').append(r.category()).append(' ').append(r.status()).append(": ").append(r.statement()).append('\n');
        }
        ModelOutcome outcome = gateway.ask(new ModelRequest(ModelPurpose.RECOMMENDATION_RANKING, instruction(shortIds.size()), List.of(DataBlock.evidence("recommendations", facts.toString())), SCHEMA));
        if (outcome instanceof ModelOutcome.Refused refused) return Narration.unavailable(refused.code().name(), refused.identity());
        ModelOutcome.Completed done = (ModelOutcome.Completed) outcome;
        @SuppressWarnings("unchecked") List<String> ranking = (List<String>) done.values().get("ranking");
        String summary = (String) done.values().get("summary");
        if (ranking.size() != shortIds.size() || !new HashSet<>(ranking).equals(shortIds.keySet()) || new HashSet<>(ranking).size() != ranking.size()) return rejected("RANKING_NOT_A_PERMUTATION", done);
        if (summary.length() > MAX_SUMMARY) return rejected("SUMMARY_TOO_LONG", done);
        if (FORBIDDEN.matcher(summary).find()) return rejected("UNSUPPORTED_CLAIM", done);
        Set<String> known = new HashSet<>();
        Matcher f = NUMBER.matcher(facts);
        while (f.find()) known.add(f.group());
        Matcher s = NUMBER.matcher(summary);
        while (s.find()) if (!known.contains(s.group())) return rejected("UNGROUNDED_NUMBER", done);
        List<String> ids = new ArrayList<>();
        for (String sid : ranking) ids.add(shortIds.get(sid));
        return new Narration("ACCEPTED", "GROUNDED", ids, summary, done.identity());
    }

    private static Narration rejected(String reason, ModelOutcome.Completed done) {
        return new Narration("REJECTED", reason, List.of(), null, done.identity());
    }

    private static String instruction(int n) {
        return "You are given " + n + " numbered maintenance recommendations (R1 to R" + n + ") built from recorded facts. Do not add, remove or change any of them."
                + " Answer with ONE JSON object with exactly: ranking (every id from R1 to R" + n + " exactly once, the most useful to read first) and summary (at most two sentences that restate only facts and numbers that appear in the recommendations)."
                + " Do not state probabilities, certainty, or that anything is faulty or will fail.";
    }
}
