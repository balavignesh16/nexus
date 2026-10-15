package com.nexus.action.outbox;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Plain-JDBC helpers shared by the P4.1 schema, migration and retention tests (no Spring, no Hibernate). */
final class OutboxSql {
    private OutboxSql() {}

    static UUID device(Connection c) throws SQLException {
        UUID site = UUID.randomUUID(), building = UUID.randomUUID(), space = UUID.randomUUID(), device = UUID.randomUUID();
        try (var s = c.createStatement()) {
            s.executeUpdate("INSERT INTO sites(id,name) VALUES ('" + site + "','S')");
            s.executeUpdate("INSERT INTO buildings(id,site_id,name) VALUES ('" + building + "','" + site + "','B')");
            s.executeUpdate("INSERT INTO spaces(id,building_id,name,created_at,updated_at) VALUES ('" + space + "','" + building + "','Sp',NOW(),NOW())");
            s.executeUpdate("INSERT INTO devices(id,space_id,name,device_type,manufacturer,model,serial_number,status,mode) VALUES ('"
                    + device + "','" + space + "','D','CUSTOM','NEXUS','M1','SN-" + device + "','ACTIVE','SIMULATED')");
        }
        return device;
    }

    /** An action_history command row; {@code status} is an ExecutionStatus name. */
    static UUID action(Connection c, UUID device, String status, Instant timestamp) throws SQLException {
        UUID id = UUID.randomUUID();
        try (PreparedStatement p = c.prepareStatement(
                "INSERT INTO action_history(request_id,match_id,device_id,action_type,status,parameters,timestamp,message) "
                        + "VALUES (?,?,?,'SEND_COMMAND',?,'{}'::jsonb,?,'test')")) {
            p.setObject(1, id);
            p.setObject(2, UUID.randomUUID());
            p.setObject(3, device);
            p.setString(4, status);
            p.setTimestamp(5, Timestamp.from(timestamp));
            p.executeUpdate();
        }
        return id;
    }

    static String payloadText(UUID commandId, long epochSeconds) {
        return "{\"commandId\":\"" + commandId + "\",\"capability\":\"LED\",\"command\":\"SET_STATE\",\"value\":true,\"timestamp\":" + epochSeconds + "}";
    }

    /** A valid QUEUED row, with any column overridden by name (including deliberately invalid values). */
    static Map<String, Object> queued(UUID commandId, UUID device, Instant now) {
        long epoch = now.getEpochSecond();
        String text = payloadText(commandId, epoch);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("command_id", commandId);
        row.put("device_id", device);
        row.put("topic", "nexus/devices/" + device + "/commands");
        row.put("payload_text", text);
        row.put("payload_sha256", OutboxPayload.sha256Hex(text));
        row.put("payload_epoch_seconds", epoch);
        row.put("state", "QUEUED");
        row.put("attempt_count", 0);
        row.put("max_attempts", 3);
        row.put("next_attempt_at", Timestamp.from(now));
        row.put("expires_at", Timestamp.from(Instant.ofEpochSecond(epoch + 15)));
        row.put("created_at", Timestamp.from(now));
        row.put("updated_at", Timestamp.from(now));
        return row;
    }

    static int insert(Connection c, Map<String, Object> row) throws SQLException {
        String columns = String.join(",", row.keySet());
        String marks = String.join(",", row.keySet().stream().map(k -> "?").toList());
        try (PreparedStatement p = c.prepareStatement("INSERT INTO command_outbox(" + columns + ") VALUES (" + marks + ")")) {
            int i = 1;
            for (Object v : row.values()) p.setObject(i++, v);
            return p.executeUpdate();
        }
    }

    static int update(Connection c, UUID commandId, String setClause) throws SQLException {
        try (var s = c.createStatement()) {
            return s.executeUpdate("UPDATE command_outbox SET " + setClause + " WHERE command_id = '" + commandId + "'");
        }
    }

    static long count(Connection c, String sql) throws SQLException {
        try (var s = c.createStatement(); var r = s.executeQuery(sql)) {
            r.next();
            return r.getLong(1);
        }
    }
}
