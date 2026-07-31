package com.nexus.action.application;

import com.nexus.action.domain.ActionType;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

@Component
public class ActionExecutorRegistry {

    private final List<ActionExecutor> executors = new CopyOnWriteArrayList<>();

    public ActionExecutorRegistry(List<ActionExecutor> initialExecutors) {
        if (initialExecutors != null) {
            executors.addAll(initialExecutors);
        }
    }

    public void register(ActionExecutor executor) {
        executors.add(executor);
    }

    public void unregister(ActionExecutor executor) {
        executors.remove(executor);
    }

    public Optional<ActionExecutor> resolve(ActionType type) {
        return executors.stream()
                .filter(e -> e.supports(type))
                .findFirst();
    }

    public List<String> list() {
        // Just for API observability
        return executors.stream()
                .map(e -> e.getClass().getSimpleName())
                .collect(Collectors.toList());
    }
}
