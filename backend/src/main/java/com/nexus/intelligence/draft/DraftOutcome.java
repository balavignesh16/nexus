package com.nexus.intelligence.draft;

import com.nexus.intelligence.model.RefusalCode;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The result of asking for a draft. {@link Created} means a proposal is stored for a human to decide; it is never a rule. A valid draft is
 * PENDING_APPROVAL, an invalid one INVALID (kept for audit, never approvable). {@link Refused} stores nothing.
 */
public sealed interface DraftOutcome permits DraftOutcome.Created, DraftOutcome.Refused {
    record Created(UUID draftId, String state, boolean valid, String definitionHash, List<Map<String, Object>> diagnostics, List<Map<String, Object>> warnings) implements DraftOutcome {}

    record Refused(DraftRefusal code, RefusalCode modelRefusal) implements DraftOutcome {}
}
