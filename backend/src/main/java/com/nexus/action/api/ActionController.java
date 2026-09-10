package com.nexus.action.api;

import com.nexus.action.api.dto.ActionHistoryResponse;
import com.nexus.action.application.ActionExecutorRegistry;
import com.nexus.action.domain.ActionHistoryRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
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

    @GetMapping("/history")
    public ResponseEntity<List<ActionHistoryResponse>> getActionHistory(
            @RequestParam(name = "limit", defaultValue = "100") int limit) {
        List<ActionHistoryResponse> responses = historyRepository.findAllByOrderByTimestampDesc(PageRequest.of(0, limit)).stream()
                .map(r -> ActionHistoryResponse.from(r.toResult()))
                .collect(Collectors.toList());
        return ResponseEntity.ok(responses);
    }

    @GetMapping("/executors")
    public ResponseEntity<List<String>> getRegisteredExecutors() {
        return ResponseEntity.ok(registry.list());
    }
}
