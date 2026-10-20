package com.nexus.rule.approval;

import java.io.PrintStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * {@code nexus-approve}: the ONLY way to record the approval or rejection of an AI-proposed rule draft (decision D1). It is a local command, not a service,
 * and opens no socket. It connects with its OWN database login ({@code NEXUS_APPROVER_DB_URL}, {@code NEXUS_APPROVER_DB_USER},
 * {@code NEXUS_APPROVER_DB_PASSWORD}, never arguments), which must be a direct member of the {@code nexus_approver} role: the database itself refuses a decision
 * from any other login, whatever this program says, and records that login, the client address and the backend process with the decision. The operating-system
 * account is recorded as a claim only; it is not what authorises anything.
 *
 * <pre>
 *   list
 *   show    --draft &lt;uuid&gt;
 *   approve --draft &lt;uuid&gt; --hash &lt;definition hash from show&gt; --reason &lt;text&gt; [--acknowledge-conflicts yes]
 *   reject  --draft &lt;uuid&gt; --hash &lt;definition hash from show&gt; --reason &lt;text&gt;
 * </pre>
 * Approval creates an INACTIVE rule version bound to the reviewed content. It never activates it: activation stays the existing lifecycle operation, whose
 * validation still runs and which the database refuses for an AI version without this approval. Exit status: 0 done, 1 refused or failed (a fixed message), 2 usage.
 */
public final class DraftApprovalCli {
    private DraftApprovalCli() {}

    public interface Connections {
        Connection open() throws SQLException;
    }

    public static void main(String[] args) {
        System.exit(run(args, System.getenv(), System.out, System.err, System.getProperty("user.name", "unknown")));
    }

    public static int run(String[] args, Map<String, String> env, PrintStream out, PrintStream err, String account) {
        String url = env.get("NEXUS_APPROVER_DB_URL"), user = env.get("NEXUS_APPROVER_DB_USER"), password = env.get("NEXUS_APPROVER_DB_PASSWORD");
        if (args.length > 0 && Set.of("list", "show", "approve", "reject").contains(args[0]) && (url == null || user == null || password == null)) {
            err.println("refused: set NEXUS_APPROVER_DB_URL, NEXUS_APPROVER_DB_USER and NEXUS_APPROVER_DB_PASSWORD");
            return 1;
        }
        return run(args, () -> DriverManager.getConnection(url, user, password), out, err, account);
    }

    public static int run(String[] args, Connections connections, PrintStream out, PrintStream err, String account) {
        if (args.length == 0) return usage(err);
        String operation = args[0];
        Map<String, String> options;
        try {
            options = options(args);
        } catch (IllegalArgumentException e) {
            return usage(err);
        }
        if (!Set.of("list", "show", "approve", "reject").contains(operation)) return usage(err);
        UUID draft = null;
        if (!operation.equals("list")) {
            try {
                draft = UUID.fromString(options.getOrDefault("draft", ""));
            } catch (IllegalArgumentException e) {
                return usage(err);
            }
        }
        if (operation.equals("approve") || operation.equals("reject")) {
            String hash = options.get("hash"), reason = options.get("reason");
            if (hash == null || !hash.matches("[0-9a-f]{64}") || reason == null || reason.isBlank() || reason.length() > 500) return usage(err);
        }
        try (Connection c = connections.open()) {
            if (!approver(c)) {
                err.println("refused: this database login is not a local approver (a direct member of the nexus_approver role)");
                return 1;
            }
            switch (operation) {
                case "list" -> list(c, out);
                case "show" -> {
                    if (!show(c, draft, out)) {
                        err.println("refused: no such draft (it may have been removed after its retention period)");
                        return 1;
                    }
                }
                default -> {
                    String decision = operation.equals("approve") ? "APPROVED" : "REJECTED";
                    String reasonText = options.get("reason");
                    if (decision.equals("APPROVED")) {
                        String status = conflictStatus(c, draft);
                        boolean clean = status.equals("CLEAR") || status.equals("NOT_APPLICABLE");
                        if (!clean && !"yes".equals(options.get("acknowledge-conflicts"))) {
                            err.println("refused: the conflict analysis of this draft is " + status + " (run show to read it); approve again with --acknowledge-conflicts yes to record that you read it");
                            return 1;
                        }
                        if (!clean) reasonText = (reasonText + " [conflict analysis " + status + " acknowledged]");
                    }
                    try (PreparedStatement p = c.prepareStatement("SELECT ai_draft_decide(?,?,?,?,?)")) {
                        p.setObject(1, draft);
                        p.setString(2, options.get("hash"));
                        p.setString(3, decision);
                        p.setString(4, reasonText.length() > 600 ? reasonText.substring(0, 600) : reasonText);
                        p.setString(5, account);
                        try (ResultSet r = p.executeQuery()) {
                            r.next();
                            out.println(decision + " decision " + r.getString(1) + " recorded for draft " + draft);
                        }
                    }
                    if (decision.equals("APPROVED")) {
                        out.println("The rule version is created INACTIVE. Activating it is a separate step through the normal rule lifecycle; it is validated again then.");
                    }
                }
            }
            return 0;
        } catch (SQLException e) {
            err.println("refused: " + message(e.getSQLState()));
            return 1;
        }
    }

    /** CLEAR, NOT_APPLICABLE, CONFLICTS, UNRESOLVED, or NOT_ANALYSED for a draft stored before the analysis existed. */
    private static String conflictStatus(Connection c, UUID draft) throws SQLException {
        try (PreparedStatement p = c.prepareStatement("SELECT validation #>> '{conflictAnalysis,status}' FROM ai_rule_drafts WHERE draft_id = ?")) {
            p.setObject(1, draft);
            try (ResultSet r = p.executeQuery()) {
                String status = r.next() ? r.getString(1) : null;
                return status == null ? "NOT_ANALYSED" : status;
            }
        }
    }

    private static boolean approver(Connection c) throws SQLException {
        try (PreparedStatement p = c.prepareStatement("SELECT ai_is_local_approver()"); ResultSet r = p.executeQuery()) {
            return r.next() && r.getBoolean(1);
        }
    }

    private static void list(Connection c, PrintStream out) throws SQLException {
        try (PreparedStatement p = c.prepareStatement("SELECT draft_id, state, valid, definition_hash, expires_at, definition->>'name' AS name FROM ai_rule_drafts WHERE state = 'PENDING_APPROVAL' ORDER BY created_at LIMIT 100");
             ResultSet r = p.executeQuery()) {
            int n = 0;
            while (r.next()) {
                n++;
                out.println(r.getString("draft_id") + "  " + r.getString("definition_hash").substring(0, 12) + "  expires " + r.getTimestamp("expires_at").toInstant() + "  " + r.getString("name"));
            }
            out.println(n + " pending draft(s)");
        }
    }

    private static boolean show(Connection c, UUID draft, PrintStream out) throws SQLException {
        try (PreparedStatement p = c.prepareStatement("SELECT state, valid, definition_hash, created_at, expires_at, provider_id, model_id, config_hash, rationale, evidence_refs::text AS refs,"
                + " validation #>> '{conflictAnalysis,status}' AS conflict, ai_conflict_stale(draft_id) AS stale, validation::text AS validation, jsonb_pretty(definition) AS definition FROM ai_rule_drafts WHERE draft_id = ?")) {
            p.setObject(1, draft);
            try (ResultSet r = p.executeQuery()) {
                if (!r.next()) return false;
                out.println("draft        " + draft);
                out.println("state        " + r.getString("state") + (r.getBoolean("valid") ? "" : "  (INVALID: cannot be approved)"));
                out.println("hash         " + r.getString("definition_hash") + "   <- pass this to approve or reject");
                out.println("created      " + r.getTimestamp("created_at").toInstant() + "   expires " + r.getTimestamp("expires_at").toInstant());
                out.println("produced by  provider " + r.getString("provider_id") + ", model " + r.getString("model_id") + ", configuration " + r.getString("config_hash"));
                out.println("evidence     " + r.getString("refs"));
                out.println("rationale    " + r.getString("rationale"));
                out.println("conflicts    " + (r.getString("conflict") == null ? "NOT_ANALYSED" : r.getString("conflict")) + "   (approve needs --acknowledge-conflicts yes unless CLEAR or NOT_APPLICABLE)");
                out.println("freshness    " + (r.getBoolean("stale") ? "STALE (approval and activation will be refused; create the draft again)" : "CURRENT"));
                out.println("validation   " + r.getString("validation"));
                out.println("definition   " + r.getString("definition"));
                return true;
            }
        }
    }

    private static String message(String sqlState) {
        if (sqlState == null) return "the database refused the request";
        return switch (sqlState) {
            case "P0A01" -> "stale conflict analysis: the rules or exclusion groups this draft was checked against have changed since it was created; create the draft again";
            case "42501" -> "this database login is not allowed to decide drafts";
            case "40001" -> "the draft changed since it was reviewed; show it again";
            case "55000" -> "the draft is not pending (already decided, expired or superseded)";
            case "P0002" -> "no such draft";
            case "23505" -> "a rule with this id already exists";
            default -> "the database refused the request (" + sqlState + ")";
        };
    }

    private static Map<String, String> options(String[] args) {
        Map<String, String> out = new java.util.HashMap<>();
        for (int i = 1; i < args.length; i++) {
            if (!args[i].startsWith("--") || i + 1 >= args.length || !Set.of("draft", "hash", "reason", "acknowledge-conflicts").contains(args[i].substring(2))) throw new IllegalArgumentException(args[i]);
            if (out.put(args[i].substring(2), args[++i]) != null) throw new IllegalArgumentException(args[i]);
        }
        return out;
    }

    private static int usage(PrintStream err) {
        err.println("usage: nexus-approve list | show --draft <uuid> | approve --draft <uuid> --hash <sha256> --reason <text> [--acknowledge-conflicts yes] | reject --draft <uuid> --hash <sha256> --reason <text>");
        return 2;
    }
}
