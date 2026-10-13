package com.nexus.rule.persistence;

import com.nexus.rule.domain.*;
import com.nexus.rule.application.RuleLifecycleService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RuleRegistryProjectionTest {
    private RuleVersionStore.View view(UUID id,long revision,String name){
        return new RuleVersionStore.View(id,"ACTIVE",revision,1,UUID.randomUUID(),UUID.randomUUID(),"VALIDATED","NOT_REQUIRED",null,false,
            new Rule(id,name,true,1,List.of(),List.of(),Map.of()),List.of());
    }
    @SuppressWarnings("unchecked") private Map<UUID,Rule> cache(RuleRegistry registry){return (Map<UUID,Rule>)ReflectionTestUtils.getField(registry,"rules");}
    @Test void oldCallbacksCannotOverwriteNewProjectionOrResurrectArchive(){
        var store=mock(RuleVersionStore.class);var registry=new RuleRegistry(mock(RuleRepository.class),mock(com.nexus.device.capability.DeviceCapabilityRepository.class),mock(com.nexus.device.domain.DeviceRepository.class));registry.setLifecycle(mock(RuleLifecycleService.class),store);
        UUID id=UUID.randomUUID();when(store.get(id)).thenReturn(Optional.of(view(id,3,"Newest")));registry.project(new RuleProjectionChanged(id,3));
        when(store.get(id)).thenReturn(Optional.of(view(id,2,"Older")));registry.project(new RuleProjectionChanged(id,2));assertEquals("Newest",cache(registry).get(id).name());
        when(store.get(id)).thenReturn(Optional.empty());registry.project(new RuleProjectionChanged(id,4));assertFalse(cache(registry).containsKey(id));
        when(store.get(id)).thenReturn(Optional.of(view(id,3,"Stale active")));registry.project(new RuleProjectionChanged(id,3));assertFalse(cache(registry).containsKey(id));
    }
    @Test void projectionFailureEvictsAndDatabaseReadFailureNeverFallsBackToCachedActive(){
        var store=mock(RuleVersionStore.class);var registry=new RuleRegistry(mock(RuleRepository.class),mock(com.nexus.device.capability.DeviceCapabilityRepository.class),mock(com.nexus.device.domain.DeviceRepository.class));registry.setLifecycle(mock(RuleLifecycleService.class),store);
        UUID id=UUID.randomUUID();when(store.get(id)).thenReturn(Optional.of(view(id,1,"Active")));registry.project(new RuleProjectionChanged(id,1));assertTrue(cache(registry).containsKey(id));
        when(store.list(true)).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("Database unavailable"));assertThrows(org.springframework.dao.DataAccessException.class,registry::listEnabledOrderedByPriority);assertTrue(cache(registry).containsKey(id),"Presentation cache cannot become execution authority on read failure");
        when(store.get(id)).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("Projection outage"));registry.project(new RuleProjectionChanged(id,2));assertFalse(cache(registry).containsKey(id));
    }
}
