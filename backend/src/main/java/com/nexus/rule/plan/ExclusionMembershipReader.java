package com.nexus.rule.plan;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/** Lock-free, read-only lookup of mutual-exclusion memberships. Safe for the dry-run engine: it never takes a row lock. */
@Repository
public class ExclusionMembershipReader {
    private final JdbcTemplate jdbc;
    public ExclusionMembershipReader(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<ExclusionMembership> read(Collection<UUID> ruleIds) {
        if (ruleIds.isEmpty()) return List.of();
        var rules = ruleIds.stream().distinct().toArray();
        String marks = String.join(",", Collections.nCopies(rules.length, "?"));
        return jdbc.query("SELECT m.group_id,m.rule_id,m.rank FROM rule_exclusion_members m WHERE m.rule_id IN (%s) ORDER BY m.group_id,m.rule_id".formatted(marks),
            (rs, n) -> new ExclusionMembership(rs.getObject("group_id", UUID.class), rs.getObject("rule_id", UUID.class), rs.getInt("rank")), rules);
    }
}
