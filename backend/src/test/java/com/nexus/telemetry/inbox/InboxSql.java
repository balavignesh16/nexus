package com.nexus.telemetry.inbox;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;

/** Plain-JDBC helpers for the P4.5 schema and migration tests (no Spring, no Hibernate). */
final class InboxSql {
    private InboxSql() {}

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

    static long count(Connection c, String sql) throws SQLException {
        try (var s = c.createStatement(); var r = s.executeQuery(sql)) {
            r.next();
            return r.getLong(1);
        }
    }

    static String text(Connection c, String sql) throws SQLException {
        try (var s = c.createStatement(); var r = s.executeQuery(sql)) {
            r.next();
            return r.getString(1);
        }
    }

    /** A dedupe row; every column except the key can be overridden to exercise the constraints. */
    static int dedupe(Connection c, UUID device, String messageId, String source, String fingerprint, UUID recordId) throws SQLException {
        try (PreparedStatement p = c.prepareStatement("INSERT INTO inbound_message_dedupe(device_id,message_id,source,fingerprint,telemetry_record_id) VALUES (?,?,?,?,?)")) {
            p.setObject(1, device);
            p.setString(2, messageId);
            p.setString(3, source);
            p.setString(4, fingerprint);
            p.setObject(5, recordId);
            return p.executeUpdate();
        }
    }

    static final String FP_A = "a".repeat(64);
    static final String FP_B = "b".repeat(64);
}
