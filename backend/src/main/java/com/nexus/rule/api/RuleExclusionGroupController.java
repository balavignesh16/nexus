package com.nexus.rule.api;

import com.nexus.rule.application.RuleExclusionGroupService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/** Additive P3.5 configuration API for mutual-exclusion groups; existing rule endpoints are unchanged. */
@RestController
@RequestMapping("/api/v1/rule-exclusion-groups")
public class RuleExclusionGroupController {
    public record CreateRequest(String name) {}
    public record MemberRequest(Integer rank) {}
    private final RuleExclusionGroupService groups;
    public RuleExclusionGroupController(RuleExclusionGroupService groups) { this.groups = groups; }

    @GetMapping public List<RuleExclusionGroupService.Group> list() { return groups.list(); }
    @GetMapping("/{groupId}") public RuleExclusionGroupService.Group get(@PathVariable UUID groupId) { return groups.get(groupId); }
    @PostMapping public RuleExclusionGroupService.Group create(@RequestBody CreateRequest request) { return groups.create(request.name()); }
    @PutMapping("/{groupId}/members/{ruleId}")
    public RuleExclusionGroupService.Group setMember(@PathVariable UUID groupId, @PathVariable UUID ruleId, @RequestBody MemberRequest request) { return groups.setMember(groupId, ruleId, request.rank()); }
    @DeleteMapping("/{groupId}/members/{ruleId}")
    public RuleExclusionGroupService.Group removeMember(@PathVariable UUID groupId, @PathVariable UUID ruleId) { return groups.removeMember(groupId, ruleId); }
    @DeleteMapping("/{groupId}") public void delete(@PathVariable UUID groupId) { groups.delete(groupId); }
}
