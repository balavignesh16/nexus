package com.nexus.twin.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.twin.domain.*;
import com.nexus.device.capability.definition.CapabilityValueType;
import com.nexus.device.binding.runtime.CapabilityResolutionSource;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TwinHistoryControllerTest {
    private final TwinStateTransitionRepository repository=mock(TwinStateTransitionRepository.class);
    private final TwinHistoryController controller=new TwinHistoryController(repository,new ObjectMapper().findAndRegisterModules());

    @Test
    void keysetCursorContinuesAfterExactTimeAndUuidTieAndLimitIsNotFingerprintBound() {
        UUID deviceId=UUID.randomUUID();
        Instant tied=Instant.parse("2026-10-06T06:00:00Z");
        TwinStateTransition first=reported(deviceId,tied,1);
        TwinStateTransition second=reported(deviceId,tied,2);
        TwinStateTransition lookahead=reported(deviceId,tied.minusSeconds(1),3);
        when(repository.search(eq(deviceId),isNull(),isNull(),isNull(),isNull(),isNull(),isNull(),isNull(),any(Pageable.class)))
                .thenReturn(List.of(first,second,lookahead));
        var page=controller.history(deviceId,null,null,null,null,null,2,null);
        assertEquals(2,page.items().size());
        assertNotNull(page.nextCursor());

        when(repository.search(eq(deviceId),isNull(),isNull(),isNull(),isNull(),isNull(),eq(tied),eq(second.getTransitionId()),any(Pageable.class)))
                .thenReturn(List.of());
        var next=controller.history(deviceId,null,null,null,null,null,50,page.nextCursor());
        assertTrue(next.items().isEmpty(),"changing limit must not invalidate the cursor fingerprint");
        verify(repository).search(eq(deviceId),isNull(),isNull(),isNull(),isNull(),isNull(),eq(tied),eq(second.getTransitionId()),any(Pageable.class));
    }

    @Test
    void rejectsInvalidBoundsSelectorsAndMismatchedCursorFilters() {
        UUID deviceId=UUID.randomUUID();
        assertThrows(IllegalArgumentException.class,()->controller.history(deviceId,UUID.randomUUID(),"TEMP",null,null,null,50,null));
        assertThrows(IllegalArgumentException.class,()->controller.history(deviceId,null,null,null,Instant.MAX,Instant.MIN,50,null));
        assertThrows(IllegalArgumentException.class,()->controller.history(deviceId,null,null,null,null,null,201,null));
        TwinStateTransition row=reported(deviceId,Instant.parse("2026-10-06T06:00:00Z"),1);
        when(repository.search(eq(deviceId),isNull(),isNull(),isNull(),isNull(),isNull(),isNull(),isNull(),any(Pageable.class)))
                .thenReturn(List.of(row,reported(deviceId,row.getTransitionedAt().minusSeconds(1),2)));
        String cursor=controller.history(deviceId,null,null,null,null,null,1,null).nextCursor();
        assertThrows(IllegalArgumentException.class,()->controller.history(deviceId,null,"TEMPERATURE_SENSOR",null,null,null,1,cursor));
        assertThrows(IllegalArgumentException.class,()->controller.history(deviceId,null,null,null,null,null,1,"not-base64"));
    }

    private static TwinStateTransition reported(UUID deviceId,Instant transitionedAt,int value){
        Instant eventTime=transitionedAt.minusSeconds(1);
        DigitalTwin twin=new DigitalTwin(deviceId,eventTime,"TEMPERATURE","TEMPERATURE_SENSOR",(double)value,"C",
                transitionedAt,transitionedAt,Map.of(),UUID.randomUUID(),null,"temperature",CapabilityValueType.NUMBER,
                CapabilityResolutionSource.LEGACY_ADAPTER);
        return TwinStateTransition.reported(null,twin,"corr-"+value,transitionedAt);
    }
}
