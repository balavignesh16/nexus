package com.nexus.action.persistence;

import com.nexus.action.domain.ActionExecutedEvent;
import com.nexus.action.domain.ActionExecutionFailedEvent;
import com.nexus.action.domain.ActionHistoryRecord;
import com.nexus.action.domain.ActionHistoryRepository;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class ActionHistoryPersister {

    private final ActionHistoryRepository repository;

    public ActionHistoryPersister(ActionHistoryRepository repository) {
        this.repository = repository;
    }

    @EventListener
    public void onActionExecuted(ActionExecutedEvent event) {
        repository.save(ActionHistoryRecord.fromResult(event.result()));
    }

    @EventListener
    public void onActionFailed(ActionExecutionFailedEvent event) {
        repository.save(ActionHistoryRecord.fromResult(event.result()));
    }
}
