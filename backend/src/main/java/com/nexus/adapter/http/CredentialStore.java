package com.nexus.adapter.http;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * What the HTTP adapter needs from persistence, as a port of the transport package (the implementation, on JDBC, is in {@code adapter.core}).
 * The backend only READS credentials and updates the last-contact column; creating, rotating and revoking is the administration CLI's job (D7).
 */
public interface CredentialStore {

    /** One credential joined with its device. {@code databaseNow} is the database clock at the read, the only clock a retire instant is judged by. */
    record Stored(UUID credentialId, UUID deviceId, String lookupId, byte[] secretHash, String algorithm, String status, Instant retireAt,
                  Instant databaseNow, String deviceStatus, String transport) {
        @Override
        public String toString() {
            return "Stored[status=" + status + "]";
        }
    }

    Optional<Stored> find(String lookupId);

    /** Records a VALID authenticated contact (database clock), written at most every few seconds per device by the implementation. */
    void recordContact(UUID credentialId, UUID deviceId);
}
