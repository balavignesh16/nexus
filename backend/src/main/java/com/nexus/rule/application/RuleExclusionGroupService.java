package com.nexus.rule.application;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.*;

/**
 * Mutual-exclusion group configuration (C16). Every mutation locks the group row FOR UPDATE; the plan transaction reads the members
 * with the group rows FOR SHARE, so a decision sees either the whole old or the whole new configuration, never a mixture. A group with
 * members cannot be deleted: a restriction only disappears by explicitly removing each member, never as a side effect.
 */
@Service
public class RuleExclusionGroupService {
    public record Member(UUID ruleId, int rank) {}
    public record Group(UUID groupId, String name, List<Member> members) {}
    private final JdbcTemplate jdbc;
    private final Clock clock;
    public RuleExclusionGroupService(JdbcTemplate jdbc, Clock clock) { this.jdbc = jdbc; this.clock = clock; }

    @Transactional
    public Group create(String name) {
        if (name == null || name.isBlank() || name.trim().length() > 120) throw new RuleMutationException(400, "A group needs a name of 1 to 120 characters");
        UUID id = UUID.randomUUID();
        try { jdbc.update("INSERT INTO rule_exclusion_groups(group_id,name,created_at) VALUES(?,?,?)", id, name.trim(), Timestamp.from(clock.instant())); }
        catch (DuplicateKeyException duplicate) { throw new RuleMutationException(409, "A group with this name already exists"); }
        return get(id);
    }

    @Transactional
    public Group setMember(UUID groupId, UUID ruleId, Integer rank) {
        ConflictStateLock.shared(jdbc);
        lock(groupId);
        if (rank == null || rank < 0) throw new RuleMutationException(400, "A member needs an explicit non-negative integer rank");
        var rule = jdbc.queryForList("SELECT lifecycle FROM rules WHERE rule_id=? FOR SHARE", ruleId);
        if (rule.isEmpty()) throw new RuleMutationException(404, "Rule not found");
        if ("ARCHIVED".equals(rule.getFirst().get("lifecycle"))) throw new RuleMutationException(409, "An archived rule cannot join a group");
        jdbc.update("INSERT INTO rule_exclusion_members(group_id,rule_id,rank) VALUES(?,?,?) ON CONFLICT (group_id,rule_id) DO UPDATE SET rank=EXCLUDED.rank", groupId, ruleId, rank);
        return get(groupId);
    }

    @Transactional
    public Group removeMember(UUID groupId, UUID ruleId) {
        ConflictStateLock.shared(jdbc);
        lock(groupId);
        if (jdbc.update("DELETE FROM rule_exclusion_members WHERE group_id=? AND rule_id=?", groupId, ruleId) == 0) throw new RuleMutationException(404, "The rule is not a member of this group");
        return get(groupId);
    }

    @Transactional
    public void delete(UUID groupId) {
        ConflictStateLock.shared(jdbc);
        lock(groupId);
        if (jdbc.queryForObject("SELECT count(*) FROM rule_exclusion_members WHERE group_id=?", Integer.class, groupId) > 0)
            throw new RuleMutationException(409, "Remove the members first: a restriction is never dropped implicitly");
        jdbc.update("DELETE FROM rule_exclusion_groups WHERE group_id=?", groupId);
    }

    @Transactional(readOnly = true)
    public Group get(UUID groupId) {
        var rows = jdbc.queryForList("SELECT group_id,name FROM rule_exclusion_groups WHERE group_id=?", groupId);
        if (rows.isEmpty()) throw new RuleMutationException(404, "Group not found");
        return new Group(groupId, rows.getFirst().get("name").toString(), members(groupId));
    }

    @Transactional(readOnly = true)
    public List<Group> list() {
        var groups = new ArrayList<Group>();
        for (var row : jdbc.queryForList("SELECT group_id,name FROM rule_exclusion_groups ORDER BY name,group_id"))
            groups.add(new Group((UUID) row.get("group_id"), row.get("name").toString(), members((UUID) row.get("group_id"))));
        return groups;
    }

    private List<Member> members(UUID groupId) {
        return jdbc.query("SELECT rule_id,rank FROM rule_exclusion_members WHERE group_id=? ORDER BY rank,rule_id",
            (rs, n) -> new Member(rs.getObject("rule_id", UUID.class), rs.getInt("rank")), groupId);
    }

    private void lock(UUID groupId) {
        if (jdbc.queryForList("SELECT 1 FROM rule_exclusion_groups WHERE group_id=? FOR UPDATE", groupId).isEmpty()) throw new RuleMutationException(404, "Group not found");
    }
}
