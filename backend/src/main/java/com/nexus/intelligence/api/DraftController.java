package com.nexus.intelligence.api;

import com.nexus.intelligence.draft.DraftOutcome;
import com.nexus.intelligence.draft.DraftService;
import com.nexus.intelligence.draft.store.DraftStore;
import com.nexus.rule.ai.ExplanationRateLimiter;
import com.nexus.shared.exception.ResourceNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Propose and read AI rule drafts. There is NO approve, reject or activate route here or anywhere else over HTTP: a decision is recorded only by the local
 * approval command with a database login that is a member of the approver role (P6-046). Creating a draft stores a proposal for a person to review; it
 * changes no rule. Unauthenticated like every NEXUS endpoint, so rate limited; the limiter establishes no identity.
 */
@RestController
@RequestMapping("/api/v1/intelligence/drafts")
public class DraftController {
    public record Request(String goal) {}

    private final DraftService drafts;
    private final DraftStore store;
    private final ExplanationRateLimiter limiter;

    public DraftController(DraftService drafts, DraftStore store, ExplanationRateLimiter limiter) {
        this.drafts = drafts;
        this.store = store;
        this.limiter = limiter;
    }

    @PostMapping
    public ResponseEntity<DraftOutcome> propose(@RequestBody Request request, HttpServletRequest http) {
        limiter.acquire(http.getRemoteAddr());
        try {
            DraftOutcome outcome = drafts.propose(request == null ? null : request.goal());
            return ResponseEntity.status(outcome instanceof DraftOutcome.Created ? HttpStatus.CREATED : HttpStatus.UNPROCESSABLE_ENTITY).body(outcome);
        } finally {
            limiter.release();
        }
    }

    @GetMapping
    public List<DraftStore.View> recent() {
        return store.recent(50);
    }

    @GetMapping("/{draftId}")
    public DraftStore.View get(@PathVariable UUID draftId) {
        return store.find(draftId).orElseThrow(() -> new ResourceNotFoundException("Draft", draftId.toString()));
    }
}
