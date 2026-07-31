package com.nexus.action.persistence;

import com.nexus.action.domain.ActionExecutedEvent;
import com.nexus.action.domain.ActionExecutionFailedEvent;
import com.nexus.action.domain.ActionExecutionResult;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

@Component
public class BoundedActionHistoryStore {
    private static final int MAX_SIZE = 1000;
    private final LinkedList<ActionExecutionResult> history = new LinkedList<>();

    @EventListener
    public synchronized void onActionExecuted(ActionExecutedEvent event) {
        add(event.result());
    }

    @EventListener
    public synchronized void onActionFailed(ActionExecutionFailedEvent event) {
        add(event.result());
    }

    private void add(ActionExecutionResult result) {
        history.addFirst(result);
        if (history.size() > MAX_SIZE) {
            history.removeLast();
        }
    }

    public synchronized List<ActionExecutionResult> getRecentHistory(int limit) {
        int toIndex = Math.min(limit, history.size());
        return new ArrayList<>(history.subList(0, toIndex));
    }

    public synchronized void clear() {
        history.clear();
    }
}
