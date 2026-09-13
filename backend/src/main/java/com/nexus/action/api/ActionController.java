package com.nexus.action.api;

import com.nexus.action.api.dto.ActionHistoryResponse;
import com.nexus.action.application.ActionExecutorRegistry;
import com.nexus.action.domain.ActionHistoryRecord;
import com.nexus.action.domain.ActionHistoryRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/actions")
public class ActionController {

    private final ActionHistoryRepository historyRepository;
    private final ActionExecutorRegistry registry;

    public ActionController(ActionHistoryRepository historyRepository, ActionExecutorRegistry registry) {
        this.historyRepository = historyRepository;
        this.registry = registry;
    }

    /**
     * Optional {@code deviceId} filter (Phase 8): a device's Actions tab must
     * only ever show actions actually recorded against that device - never
     * every action in the system. Omitted, this preserves the original
     * unfiltered behavior for existing callers.
     */
    @GetMapping("/history")
    public ResponseEntity<List<ActionHistoryResponse>> getActionHistory(
            @RequestParam(name = "limit", defaultValue = "100") int limit,
            @RequestParam(name = "deviceId", required = false) UUID deviceId) {
        Page<ActionHistoryRecord> page = deviceId != null
                ? historyRepository.findByDeviceIdOrderByTimestampDesc(deviceId, PageRequest.of(0, limit))
                : historyRepository.findAllByOrderByTimestampDesc(PageRequest.of(0, limit));
        List<ActionHistoryResponse> responses = page.stream()
                .map(ActionHistoryResponse::from)
                .collect(Collectors.toList());
        return ResponseEntity.ok(responses);
    }

    @GetMapping("/executors")
    public ResponseEntity<List<String>> getRegisteredExecutors() {
        return ResponseEntity.ok(registry.list());
    }
}
