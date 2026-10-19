package com.nexus.rule.ai;

import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A deterministic deny-list for model-written prose, covering the four assertion classes that no persisted evidence tier in this platform can establish in
 * words: PHYSICAL ACTUATION ("the motor turned on"), DEVICE RECEIPT OR EXECUTION ("the device received the command"), HUMAN APPROVAL ("approved by an operator")
 * and FAULTS OR CAUSES ("the sensor is faulty", "silent because of a power loss"). A citation that resolves and a value that matches prove that the CITED
 * FACT is true; they say nothing about what a sentence built around that fact goes on to claim, and a sentence with no invented number can still be
 * unsupported.
 *
 * <p>WHAT THIS IS NOT. It is not a semantic verifier and does not make free prose faithful. It recognises a closed list of wordings after normalising the
 * text (compatibility form, invisible and zero-width characters removed, whitespace collapsed, case folded); a paraphrase outside the list passes. A match
 * that follows a negation or doubt cue in the same sentence ("this is not evidence of actuation", "whether the device received it is unknown") is a denial,
 * not an assertion, and is allowed. Recorded connectivity words such as "offline" are facts when cited and are deliberately not on the list. The job of the
 * guard is to stop the safety-critical classes from being stated in obvious wording; where the platform needs a statement to be true by construction it uses
 * platform-owned or extractive text instead (see RecommendationNarrator). The evaluation measures the residual honestly.
 */
public final class UnsupportedAssertionGuard {
    private UnsupportedAssertionGuard() {}

    public enum Kind { PHYSICAL_ACTUATION, RECEIPT_OR_EXECUTION, HUMAN_APPROVAL, FAULT_OR_CAUSE }

    private static final Map<Kind, Pattern> PATTERNS = new LinkedHashMap<>();

    static {
        PATTERNS.put(Kind.PHYSICAL_ACTUATION, Pattern.compile(
                "\\b(turned|switched|powered|flipped)\\s+(it\\s+|them\\s+)?(on|off)\\b"
                        + "|\\bactuat\\w*"
                        + "|\\b(was|were|been|got|is|are)\\s+(now\\s+)?(opened|closed|started|stopped|energi[sz]ed|toggled|running|spinning)\\b"
                        + "|\\bphysical(ly)?\\s+(action|effect|outcome|change|result|movement|state)\\b"
                        + "|\\btook effect\\b|\\bhad the (desired|intended|expected) effect\\b"
                        + "|\\b(heater|motor|relay|led|lamp|light|valve|fan|pump|door|window|actuator|switch)\\b[^.]{0,40}\\b(is|was|are|were|has been|have been)\\s+(now\\s+)?(on|off|open|closed|running|engaged|working)\\b"));
        PATTERNS.put(Kind.RECEIPT_OR_EXECUTION, Pattern.compile(
                "\\b(device|node|controller|esp32|sensor|relay|actuator|it|they)\\s+(has\\s+|had\\s+|then\\s+)?(received|acknowledged|acked|executed|applied|performed|confirmed|obeyed|complied|carried out|accepted)\\b"
                        + "|\\b(command|request|instruction|message|action)s?\\s+(was|were|has been|have been|had been|got)\\s+(received|delivered|executed|applied|carried out|performed|completed|confirmed|acknowledged|run)\\b"
                        + "|\\b(command|request|instruction|message|action)s?\\s+(succeeded|worked|went through|took|was successful|were successful)\\b"
                        + "|\\b(successfully|successful)\\s+(delivered|executed|completed|applied|performed|run|sent)\\b"
                        + "|\\bdelivered to the (device|node|controller)\\b"));
        PATTERNS.put(Kind.HUMAN_APPROVAL, Pattern.compile(
                "\\b(approved|authori[sz]ed|signed off|endorsed|cleared|verified)\\s+by\\b"
                        + "|\\b(was|been|is|were|has been|have been)\\s+(approved|authori[sz]ed|signed off)\\b"
                        + "|\\b(administrator|admin|operator|engineer|user|human|manager|supervisor|technician)s?\\s+((has|have|had)\\s+)?((already|previously|just|duly|formally|officially|also)\\s+)?(approved|authori[sz]ed|signed off|accepted|confirmed)\\b"
                        + "|\\b(approved|authori[sz]ed|signed off)\\s+(this|the|that|it|these|those)\\b"
                        + "|\\bhuman (approval|review|sign-?off|confirmation)\\b"
                        + "|\\bapproval (was|has been|is) (granted|given|recorded|obtained)\\b"));
        PATTERNS.put(Kind.FAULT_OR_CAUSE, Pattern.compile(
                "\\b(is|was|are|were|seems|appears|looks|must be|might be|probably|likely)\\s+(to be\\s+|to have\\s+)?(faulty|broken|defective|failing|malfunctioning|miscalibrated|damaged|dead|unplugged|out of calibration|stuck)\\b"
                        + "|\\b(hardware|sensor|device|battery|power|network|wifi|wi-fi|firmware|connection)\\s+(fault|failure|malfunction|defect|loss|outage|problem|issue|error|drop)\\b"
                        + "|\\bcalibration\\s+(error|problem|issue|drift|fault|offset)\\b"
                        + "|\\b(because|since|due to|owing to|caused by|as a result of|result of)\\b[^.]{0,30}\\b(sensor|device|hardware|network|power|battery|wifi|wi-fi|firmware|connection|calibration|fault|failure)\\b"));
    }

    /**
     * A cue that makes the words after it a denial or a doubt rather than an assertion ("this is not evidence of physical actuation", "whether the device
     * received it is unknown"). Looked for earlier in the SAME sentence, within 80 characters. "not only" is excluded: it introduces an assertion.
     */
    private static final Pattern NEGATION = Pattern.compile("\\b(not(?! only)|no|never|cannot|can't|isn't|aren't|wasn't|weren't|doesn't|don't|didn't|without|nor|neither|nothing|none|nobody|unknown|unverified|unconfirmed|whether|if|unless|rather than)\\b");

    /** @return the first violated class, or null when none of the closed wordings is asserted. */
    public static Kind violation(String text) {
        if (text == null || text.isEmpty()) return null;
        String normalised = normalise(text);
        for (Map.Entry<Kind, Pattern> e : PATTERNS.entrySet()) {
            Matcher m = e.getValue().matcher(normalised);
            while (m.find()) if (!negated(normalised, m.start())) return e.getKey();
        }
        return null;
    }

    private static boolean negated(String text, int matchStart) {
        int sentenceStart = Math.max(text.lastIndexOf('.', matchStart - 1), Math.max(text.lastIndexOf('!', matchStart - 1), text.lastIndexOf('?', matchStart - 1))) + 1;
        int from = Math.max(sentenceStart, matchStart - 80);
        return from < matchStart && NEGATION.matcher(text.substring(from, matchStart)).find();
    }

    /** NFKC, invisible/format/control characters removed, whitespace collapsed, lower case: so "turned​on" and "ＴＵＲＮＥＤ on" are read like the plain wording. */
    static String normalise(String text) {
        String s = Normalizer.normalize(text, Normalizer.Form.NFKC);
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            int type = Character.getType(cp);
            if (type == Character.FORMAT || type == Character.CONTROL && !Character.isWhitespace(cp)) continue; // zero-width joiners, bidi marks, BOM
            sb.appendCodePoint(Character.isWhitespace(cp) ? ' ' : cp);
        }
        return sb.toString().replaceAll("\\s+", " ").toLowerCase(java.util.Locale.ROOT);
    }
}
