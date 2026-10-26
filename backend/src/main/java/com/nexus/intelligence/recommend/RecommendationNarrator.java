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
import java.util.regex.Pattern;

/**
 * OPTIONAL model help for reading a list of recommendations: it may put them in a suggested reading order and choose which of the platform's own sentences to
 * show as a short summary. It can introduce no recommendation and change none. The model is reached only through the {@link ModelGateway} (enabled gate,
 * device-evidence consent, bounded context, strict JSON).
 *
 * <p>CONSTRAINED WORDING, NOT FREE PROSE. Whether a model sentence is faithful to the evidence cannot be verified automatically, and a sentence with no
 * invented number can still invent a fault, a reason for silence, a success, a receipt or an approval. So the summary is accepted only if EVERY sentence is,
 * word for word, a sentence of the platform-written recommendation statements it was shown (at most three). The model chooses and orders; it does not write.
 * Anything else is rejected with a fixed reason and the deterministic list stands alone. Limit: a true sentence can still be shown out of its context (for
 * example the observation without its caveat), which is why the full statements are always returned beside the selection.
 */
@Service
public class RecommendationNarrator {
    public record Narration(String status, String reason, List<String> rankedIds, String summary, ProviderIdentity generatedBy) {
        static Narration unavailable(String reason, ProviderIdentity id) {
            return new Narration("UNAVAILABLE", reason, List.of(), null, id);
        }
    }

    static final int MAX_SUMMARY = 600;
    static final int MAX_SENTENCES = 3;
    private static final Pattern SENTENCE_BOUNDARY = Pattern.compile("(?<=[.!?])\\s+");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
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
        Set<String> platformSentences = new HashSet<>();
        for (Recommendation r : recommendations) {
            String sid = "R" + (shortIds.size() + 1);
            shortIds.put(sid, r.id());
            facts.append(sid).append(' ').append(r.category()).append(' ').append(r.status()).append(": ").append(r.statement()).append('\n');
            for (String sentence : SENTENCE_BOUNDARY.split(r.statement().strip())) platformSentences.add(squash(sentence));
        }
        ModelOutcome outcome = gateway.ask(new ModelRequest(ModelPurpose.RECOMMENDATION_RANKING, instruction(shortIds.size()), List.of(DataBlock.evidence("recommendations", facts.toString())), SCHEMA));
        if (outcome instanceof ModelOutcome.Refused refused) return Narration.unavailable(refused.code().name(), refused.identity());
        ModelOutcome.Completed done = (ModelOutcome.Completed) outcome;
        @SuppressWarnings("unchecked") List<String> ranking = (List<String>) done.values().get("ranking");
        String summary = (String) done.values().get("summary");
        if (ranking.size() != shortIds.size() || !new HashSet<>(ranking).equals(shortIds.keySet()) || new HashSet<>(ranking).size() != ranking.size()) return rejected("RANKING_NOT_A_PERMUTATION", done);
        if (summary.length() > MAX_SUMMARY) return rejected("SUMMARY_TOO_LONG", done);
        String[] chosen = SENTENCE_BOUNDARY.split(summary.strip());
        if (summary.isBlank() || chosen.length > MAX_SENTENCES) return rejected("SUMMARY_NOT_A_SHORT_SELECTION", done);
        for (String sentence : chosen) if (!platformSentences.contains(squash(sentence))) return rejected("UNSUPPORTED_SENTENCE", done);
        List<String> ids = new ArrayList<>();
        for (String sid : ranking) ids.add(shortIds.get(sid));
        return new Narration("ACCEPTED", "EXTRACTIVE_SELECTION", ids, summary, done.identity());
    }

    private static String squash(String sentence) {
        return WHITESPACE.matcher(sentence.strip()).replaceAll(" ");
    }

    private static Narration rejected(String reason, ModelOutcome.Completed done) {
        return new Narration("REJECTED", reason, List.of(), null, done.identity());
    }

    private static String instruction(int n) {
        return "You are given " + n + " numbered maintenance recommendations (R1 to R" + n + ") built from recorded facts. Do not add, remove or change any of them."
                + " Answer with ONE JSON object with exactly: ranking (every id from R1 to R" + n + " exactly once, the most useful to read first) and summary"
                + " (one to three sentences COPIED WORD FOR WORD from the recommendations; do not write or rephrase anything)."
                + " Anything that is not an exact sentence of the recommendations is discarded.";
    }
}
