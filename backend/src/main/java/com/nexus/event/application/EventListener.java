package com.nexus.event.application;

import com.nexus.event.domain.DomainEvent;

public interface EventListener {
    void onEvent(DomainEvent event);
}
