package com.nexus.telemetry.application;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * The insert-first step of the telemetry inbox (R17). The claim is one {@code INSERT ... ON CONFLICT DO NOTHING} on the
 * primary key (device_id, message_id), made in the caller's transaction (MANDATORY): the claim commits, or rolls back, together with
 * the telemetry write, the Twin update, the verification and rule facts and the onboarding step it protects. It can therefore
 * never record a message as seen that was not stored, and a failure after the claim removes the claim again.
 *
 * <p>Concurrent identical submissions serialise on the unique index: the second statement waits for the first transaction and
 * then sees the committed row (a conflict) or, if the first rolled back, claims the id itself. The outcome of a conflict is
 * decided by comparing fingerprints, so a retransmission is a harmless duplicate and a different payload under the same id is a
 * conflict; the first submission wins in both cases.
 */
@Component
public class InboundMessageDedupe {
    /** A claim attempt that finds its row gone again (purged between the two statements) tries a few more times before giving up. */
    private static final int MAX_ATTEMPTS = 3;

    public sealed interface Claim permits Claimed, Duplicate, Conflict {}
    /** First sight of this id: the caller must now process the sample. */
    public record Claimed() implements Claim {}
    /** Same id, same fingerprint: already accepted. {@code originalRecordId} is the stored sample's id, or null when unknown. */
    public record Duplicate(UUID originalRecordId) implements Claim {}
    /** Same id, different fingerprint. */
    public record Conflict() implements Claim {}

    private final JdbcTemplate jdbc;

    @Autowired
    public InboundMessageDedupe(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Claim claim(UUID deviceId, String messageId, TelemetrySource source, String fingerprint, UUID telemetryRecordId) {
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            int inserted = jdbc.update("INSERT INTO inbound_message_dedupe (device_id, message_id, source, fingerprint, telemetry_record_id)"
                    + " VALUES (?, ?, ?, ?, ?) ON CONFLICT (device_id, message_id) DO NOTHING",
                    deviceId, messageId, source.name(), fingerprint, telemetryRecordId);
            if (inserted == 1) return new Claimed();
            List<Claim> existing = jdbc.query("SELECT fingerprint, telemetry_record_id FROM inbound_message_dedupe WHERE device_id = ? AND message_id = ?",
                    (rs, row) -> rs.getString("fingerprint").equals(fingerprint)
                            ? (Claim) new Duplicate(rs.getObject("telemetry_record_id", UUID.class))
                            : new Conflict(),
                    deviceId, messageId);
            if (!existing.isEmpty()) return existing.getFirst();
        }
        throw new IllegalStateException("Could not claim or read the inbound message entry for device " + deviceId + "; it is being purged concurrently");
    }
}
