package com.nexus.rule.trigger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Derives COMMAND_ACK_TIMED_OUT facts from stored ACK deadlines (C12): strictly after the deadline, once per command,
 * and only while no authoritative ACK exists. It never resends or republishes anything and shares no table with
 * Twin verification deadlines.
 */
@Component
public class RuleAckTimeoutScanner {
    private static final Logger log=LoggerFactory.getLogger(RuleAckTimeoutScanner.class);
    private final JdbcTemplate jdbc;
    private final PlatformTransactionManager transactions;
    private final RuleSourceFacts facts;
    private final Clock clock;
    public RuleAckTimeoutScanner(JdbcTemplate jdbc,PlatformTransactionManager transactions,RuleSourceFacts facts,Clock clock){
        this.jdbc=jdbc;this.transactions=transactions;this.facts=facts;this.clock=clock;
    }

    @Scheduled(fixedDelayString="${nexus.rule.ack-timeout-scan-ms:5000}")
    public void scheduled(){
        try{scan(100);}catch(RuntimeException failure){log.warn("ACK timeout scan unavailable: {}",failure.getMessage());}
    }

    /** @return number of timeout facts newly recorded */
    public int scan(int limit){
        Instant now=clock.instant();
        var due=jdbc.queryForList("""
            SELECT a.request_id,a.device_id FROM action_history a
            WHERE a.status='SUCCESS' AND a.ack_status IS NULL AND a.ack_deadline_at IS NOT NULL AND a.ack_deadline_at < ?
              AND NOT EXISTS (SELECT 1 FROM rule_trigger_facts f WHERE f.trigger_type='COMMAND_ACK_TIMED_OUT' AND f.source_key=a.request_id::text)
            ORDER BY a.ack_deadline_at LIMIT ?""",Timestamp.from(now),limit);
        int recorded=0;
        var tx=new TransactionTemplate(transactions);
        for(var row:due){
            UUID commandId=(UUID)row.get("request_id");UUID deviceId=(UUID)row.get("device_id");
            Boolean written=tx.execute(state->{
                // Re-read under the row lock: a late-arriving ACK or a changed deadline supersedes the derivation.
                var current=jdbc.queryForList("SELECT ack_status,ack_deadline_at,status FROM action_history WHERE request_id=? FOR UPDATE",commandId);
                if(current.isEmpty())return false;
                var r=current.getFirst();
                if(r.get("ack_status")!=null||!"SUCCESS".equals(String.valueOf(r.get("status")))||r.get("ack_deadline_at")==null)return false;
                Instant deadline=((Timestamp)r.get("ack_deadline_at")).toInstant();
                if(!now.isAfter(deadline))return false; // equality is not a timeout
                facts.ackTimedOut(commandId,deviceId,deadline);
                return true;
            });
            if(Boolean.TRUE.equals(written))recorded++;
        }
        return recorded;
    }
}
