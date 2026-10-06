package com.nexus.twin.domain;

import com.nexus.device.capability.definition.CapabilityValueType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class TwinStateTransitionTest {
    @Test
    void desiredTransitionKeepsCommandCorrelationSubtargetAndDesiredTimesSeparateFromReceiptTime() {
        UUID deviceId=UUID.randomUUID();
        UUID bindingId=UUID.randomUUID();
        UUID definitionId=UUID.randomUUID();
        UUID firstCommand=UUID.randomUUID();
        UUID secondCommand=UUID.randomUUID();
        Instant requested=Instant.parse("2026-10-06T06:00:00Z");
        Instant accepted=Instant.parse("2026-10-06T06:00:01Z");
        DesiredTwinState previous=new DesiredTwinState(deviceId,"binding:"+bindingId,bindingId,null,"channel-1",
                definitionId,"relay",CapabilityValueType.BOOLEAN,true,null,"MANUAL_COMMAND",firstCommand,
                requested.minusSeconds(2),accepted.minusSeconds(2));
        DesiredTwinState next=new DesiredTwinState(deviceId,"binding:"+bindingId,bindingId,null,"channel-1",
                definitionId,"relay",CapabilityValueType.BOOLEAN,true,null,"MANUAL_COMMAND",secondCommand,
                requested,accepted);

        TwinStateTransition row=TwinStateTransition.desired(previous,next,accepted.plusSeconds(1),"corr-2");

        assertEquals(true,row.previousValue());
        assertEquals(true,row.newValue());
        assertEquals(secondCommand,row.getCommandId());
        assertEquals("corr-2",row.getCorrelationId());
        assertEquals("channel-1",row.getSubtargetKey());
        assertEquals(definitionId,row.getCapabilityDefinitionId());
        assertEquals("relay",row.getCapabilityKey());
        assertEquals(requested,row.getRequestedAt());
        assertEquals(accepted,row.getAcceptedForDispatchAt());
        assertNull(row.getReceivedAt(),"desired acceptance time is not telemetry receipt time");
    }
}
