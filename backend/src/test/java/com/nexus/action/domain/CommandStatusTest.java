package com.nexus.action.domain;

import org.junit.jupiter.api.Test;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class CommandStatusTest {
    @Test void ackEvidenceTakesPrecedenceWithoutRewritingExecutionStatus() {
        var row=new ActionHistoryRecord(UUID.randomUUID(),null,UUID.randomUUID(),ActionType.SEND_COMMAND,
                ExecutionStatus.SUCCESS,Map.of(),Instant.parse("2026-10-01T00:00:00Z"),"published");
        Clock later=Clock.fixed(Instant.parse("2026-10-01T00:01:00Z"),ZoneOffset.UTC);
        assertEquals(CommandStatus.PUBLISHED,CommandStatus.from(row,later));
        org.springframework.test.util.ReflectionTestUtils.setField(row,"ackDeadlineAt",
                Instant.parse("2026-10-01T00:00:30Z"));
        assertEquals(CommandStatus.TIMED_OUT,CommandStatus.from(row,later));
        assertEquals(CommandStatus.PUBLISHED,CommandStatus.from(row,
                Clock.fixed(Instant.parse("2026-10-01T00:00:30Z"),ZoneOffset.UTC)));
        row.applyAcknowledgement(AcknowledgementStatus.ACCEPTED,Instant.parse("2026-10-01T00:00:10Z"),null);
        assertEquals(CommandStatus.ACKNOWLEDGED,CommandStatus.from(row,later));
        assertEquals(ExecutionStatus.SUCCESS,row.getStatus());
        row.applyAcknowledgement(AcknowledgementStatus.REJECTED,Instant.parse("2026-10-01T00:00:10Z"),"rejected");
        assertEquals(CommandStatus.REJECTED,CommandStatus.from(row,later));
    }
}
