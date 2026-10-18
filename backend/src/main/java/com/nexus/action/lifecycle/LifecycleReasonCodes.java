package com.nexus.action.lifecycle;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Which recorded reason codes the lifecycle API and the lifecycle traces may show (contract R37, ledger P4-101). A reason recorded by the
 * platform itself is a fixed code and is shown as recorded. A reason that originated in a DEVICE message (facts written by the ACK handler)
 * is shown only when it is one of the codes the NEXUS firmware sends or the platform substitutes; any other device-chosen text, even after
 * the persistence layer reduced it to upper-case letters, digits and underscores, is shown as {@value #OTHER}. The stored history row is not
 * changed; this only limits what a read-only response or trace copies out of it.
 */
final class LifecycleReasonCodes {
    static final String OTHER = "DEVICE_REASON_OTHER";
    static final List<String> DEVICE_VISIBLE = List.of("MALFORMED", "UNSUPPORTED", "STALE", "DEVICE_REJECTED", "ACCEPTED", "REJECTED", "CONFLICTING_ACK");

    private LifecycleReasonCodes() {}

    static String visible(String source, String reason) {
        if (reason == null || !"ACK_HANDLER".equals(source)) return reason;
        return DEVICE_VISIBLE.contains(reason) ? reason : OTHER;
    }

    /** The same rule as a SQL expression over the columns {@code source} and {@code reason_code} of the given table alias. */
    static String sql(String alias) {
        String list = DEVICE_VISIBLE.stream().map(c -> "'" + c + "'").collect(Collectors.joining(", "));
        return "CASE WHEN " + alias + ".source = 'ACK_HANDLER' AND " + alias + ".reason_code IS NOT NULL AND " + alias + ".reason_code NOT IN (" + list
                + ") THEN '" + OTHER + "' ELSE " + alias + ".reason_code END";
    }
}
