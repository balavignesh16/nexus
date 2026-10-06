package com.nexus.action.persistence;

import com.nexus.action.domain.ActionExecutedEvent;
import com.nexus.action.domain.ActionExecutionFailedEvent;
import com.nexus.action.domain.ActionHistoryRecord;
import com.nexus.action.domain.ActionHistoryRepository;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class ActionHistoryPersister {

    private final ActionHistoryRepository repository;

    public ActionHistoryPersister(ActionHistoryRepository repository) {
        this.repository = repository;
    }

    @EventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onActionExecuted(ActionExecutedEvent event) {
        repository.save(ActionHistoryRecord.fromResult(event.result()));
    }

    @EventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onActionFailed(ActionExecutionFailedEvent event) {
        repository.save(ActionHistoryRecord.fromResult(event.result()));
    }
}
