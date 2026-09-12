package com.nexus.rule.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The defensive boundary between untrusted, future model JSON output and
 * internal Java records (Tier 4 Phase 2A). Parses raw JSON text into a
 * RuleMatchExplanation candidate, guaranteeing no exception escapes this
 * class for any malformed input.
 *
 * Deliberately does NOT duplicate every shape rule GroundingChecker
 * already enforces (e.g. "FACT requires evidenceRef") - a malformed
 * individual claim that would fail one of those rules is safely skipped
 * here (recorded in skippedClaimReasons) and simply never reaches
 * GroundingChecker, which independently re-validates whatever does
 * survive. This class exists only for concerns unique to the raw-JSON
 * boundary: preventing crashes (null/wrong-typed elements that would
 * otherwise NPE or throw during record construction) and the one
 * distinction a constructed Claim can no longer make - whether the
 * "assertedValue" JSON key was present at all, as opposed to present with
 * value null. A FACT claim missing that key is skipped here; one that
 * sets it explicitly to null is preserved as a valid "asserts null" claim.
 *
 * generatedBy is never read from candidate JSON, even if present - it is
 * always set to "AI" here, since only this parser (used exclusively for
 * AI-origin candidates) constructs it.
 */
@Component
public class AiCandidateParser {

    private final ObjectMapper objectMapper;

    public AiCandidateParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public AiParseResult parse(String rawJson) {
        JsonNode root;
        try {
            root = objectMapper.readTree(rawJson);
        } catch (Exception e) {
            return AiParseResult.malformed("invalid JSON: " + e.getMessage());
        }
        if (root == null || !root.isObject()) {
            return AiParseResult.malformed("top-level JSON must be an object");
        }

        UUID matchId = readUuid(root, "matchId");
        if (matchId == null) {
            return AiParseResult.malformed("missing or invalid matchId");
        }

        String summary = readNonBlankText(root, "summary");
        if (summary == null) {
            return AiParseResult.malformed("missing or blank summary");
        }

        JsonNode claimsNode = root.get("claims");
        if (claimsNode == null || !claimsNode.isArray()) {
            return AiParseResult.malformed("missing or invalid claims array");
        }

        List<Claim> claims = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (JsonNode claimNode : claimsNode) {
            Claim claim = parseClaimOrNull(claimNode, skipped);
            if (claim != null) {
                claims.add(claim);
            }
        }

        // actionNote is NEXUS-owned - never read from candidate JSON here;
        // GroundingChecker overwrites it unconditionally regardless.
        RuleMatchExplanation candidate = new RuleMatchExplanation(matchId, summary, claims, null, "AI");
        return AiParseResult.success(candidate, skipped);
    }

    private Claim parseClaimOrNull(JsonNode node, List<String> skipped) {
        if (node == null || node.isNull() || !node.isObject()) {
            skipped.add("skipped a null or non-object claims[] element");
            return null;
        }

        JsonNode tierNode = node.get("tier");
        if (tierNode == null || !tierNode.isTextual()) {
            skipped.add("skipped claim with missing or invalid tier");
            return null;
        }
        ClaimTier tier;
        try {
            tier = ClaimTier.valueOf(tierNode.asText());
        } catch (IllegalArgumentException e) {
            skipped.add("skipped claim with unknown tier: " + tierNode.asText());
            return null;
        }

        String text = readNonBlankText(node, "text");
        if (text == null) {
            skipped.add("skipped claim with missing or blank text");
            return null;
        }

        String evidenceRef = readTextOrNull(node, "evidenceRef");

        JsonNode supportingNode = node.get("supportingEvidenceRefs");
        if (supportingNode == null || !supportingNode.isArray()) {
            skipped.add("skipped claim with missing or invalid supportingEvidenceRefs");
            return null;
        }
        List<String> supportingEvidenceRefs = new ArrayList<>();
        for (JsonNode ref : supportingNode) {
            if (ref == null || !ref.isTextual()) {
                skipped.add("skipped claim with a non-string element in supportingEvidenceRefs");
                return null;
            }
            supportingEvidenceRefs.add(ref.asText());
        }

        boolean hasAssertedValueKey = node.has("assertedValue");
        if (tier == ClaimTier.FACT && !hasAssertedValueKey) {
            skipped.add("skipped FACT claim missing required assertedValue key");
            return null;
        }
        if (tier != ClaimTier.FACT && hasAssertedValueKey) {
            skipped.add("skipped " + tier + " claim that illegally provided assertedValue");
            return null;
        }
        Object assertedValue = hasAssertedValueKey ? toJavaValue(node.get("assertedValue")) : null;

        return new Claim(tier, text, evidenceRef, assertedValue, supportingEvidenceRefs);
    }

    private UUID readUuid(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) {
            return null;
        }
        try {
            return UUID.fromString(value.asText());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private String readNonBlankText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            return null;
        }
        return value.asText();
    }

    private String readTextOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || !value.isTextual()) {
            return null;
        }
        return value.asText();
    }

    private Object toJavaValue(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            return node.asText();
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        if (node.isIntegralNumber()) {
            return node.asLong();
        }
        if (node.isNumber()) {
            return node.asDouble();
        }
        // Objects/arrays are not expected for assertedValue; fall back to
        // their textual form rather than throwing.
        return node.toString();
    }
}
