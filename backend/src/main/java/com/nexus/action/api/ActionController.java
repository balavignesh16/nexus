package com.nexus.action.api;

import com.nexus.action.api.dto.ActionHistoryResponse;
import com.nexus.action.application.ActionExecutorRegistry;
import com.nexus.action.domain.ActionHistoryRecord;
import com.nexus.action.domain.ActionHistoryRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Autowired;

@RestController
@RequestMapping("/api/v1/actions")
public class ActionController {

    private final ActionHistoryRepository historyRepository;
    private final ActionExecutorRegistry registry;
    private final Clock clock;
    private com.nexus.action.outbox.CommandOutboxRepository outbox;
    private com.nexus.action.lifecycle.CommandLifecycleReader lifecycle;

    /** Optional: marks the rows of commands accepted before the outbox existed as DELIVERY_UNKNOWN (P4-073); absent in unit tests. */
    @Autowired(required = false)
    void setLifecycle(com.nexus.action.lifecycle.CommandLifecycleReader lifecycle) { this.lifecycle = lifecycle; }

    /** Optional: when present, history rows of outbox commands carry their additive delivery state (P4-103); absent in unit tests. */
    @Autowired(required = false)
    void setOutbox(com.nexus.action.outbox.CommandOutboxRepository outbox) { this.outbox = outbox; }

    public ActionController(ActionHistoryRepository historyRepository, ActionExecutorRegistry registry) {
        this(historyRepository,registry,Clock.systemUTC());
    }

    @Autowired
    public ActionController(ActionHistoryRepository historyRepository, ActionExecutorRegistry registry,Clock clock) {
        this.historyRepository = historyRepository;
        this.registry = registry;
        this.clock = clock;
    }

    /**
     * Optional {@code deviceId} filter (Phase 8): a device's Actions tab must
     * only ever show actions actually recorded against that device - never
     * every action in the system. Optional {@code matchId} filter (Phase 3):
     * lets a rule-match investigation view show exactly the real actions it
     * triggered, via the already-persisted ActionHistoryRecord.matchId FK -
     * reuses the existing repository method already used by
     * RuleMatchExplanationContextBuilder, so this is not a new relationship.
     * At most one filter may be supplied at a time; omitted, both preserve
     * the original unfiltered behavior for existing callers.
     */
    @GetMapping("/history")
    public ResponseEntity<List<ActionHistoryResponse>> getActionHistory(
            @RequestParam(name = "limit", defaultValue = "100") int limit,
            @RequestParam(name = "deviceId", required = false) UUID deviceId,
            @RequestParam(name = "matchId", required = false) UUID matchId) {
        List<ActionHistoryRecord> records;
        if (matchId != null) {
            records = historyRepository.findByMatchId(matchId);
        } else if (deviceId != null) {
            records = historyRepository.findByDeviceIdOrderByTimestampDesc(deviceId, PageRequest.of(0, limit)).getContent();
        } else {
            records = historyRepository.findAllByOrderByTimestampDesc(PageRequest.of(0, limit)).getContent();
        }
        // One bounded lookup for the whole page (never one query per row): the delivery state of the rows that have an outbox row.
        java.util.Map<UUID, com.nexus.action.outbox.CommandOutboxEntity> delivery = new java.util.HashMap<>();
        if (outbox != null && !records.isEmpty())
            outbox.findAllById(records.stream().map(ActionHistoryRecord::getRequestId).toList()).forEach(o -> delivery.put(o.getCommandId(), o));
        java.util.Set<UUID> unknown = lifecycle == null ? java.util.Set.of()
                : lifecycle.deliveryUnknownAmong(records.stream().map(ActionHistoryRecord::getRequestId).filter(id -> !delivery.containsKey(id)).toList());
        List<ActionHistoryResponse> responses = records.stream()
                .map(row -> {
                    var response = ActionHistoryResponse.from(row,clock);
                    var o = delivery.get(row.getRequestId());
                    if (o != null) return response.withDelivery(o.getState().name(), o.getTerminalReason() == null ? null : o.getTerminalReason().name());
                    // No outbox row: a command the reconciler classified (accepted before the outbox existed) reports DELIVERY_UNKNOWN additively; its
                    // existing status field keeps its prior meaning.
                    return unknown.contains(row.getRequestId()) ? response.withDelivery("DELIVERY_UNKNOWN", "LEGACY_DELIVERY_UNRECORDED") : response;
                })
                .collect(Collectors.toList());
        return ResponseEntity.ok(responses);
    }

    @GetMapping("/executors")
    public ResponseEntity<List<String>> getRegisteredExecutors() {
        return ResponseEntity.ok(registry.list());
    }
}
