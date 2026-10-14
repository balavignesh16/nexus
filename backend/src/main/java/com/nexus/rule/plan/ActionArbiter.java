package com.nexus.rule.plan;

import com.nexus.rule.domain.RuleAction;
import java.util.*;

/**
 * Deterministic C16 arbitration over one trigger batch. Same target + identical command: coalesce and keep all
 * contributors; conflicting: smaller numeric priority wins; equal-priority conflict: suppress every conflicting
 * candidate. Ordering uses priority, rule id, revision and ordinal, never repository iteration order.
 */
public final class ActionArbiter {
    private ActionArbiter(){}

    public static final Comparator<ActionCandidate> ORDER=Comparator.comparingInt(ActionCandidate::priority)
        .thenComparing(c->c.rule().ruleId().toString()).thenComparingLong(ActionCandidate::revision).thenComparingInt(ActionCandidate::ordinal);

    public static List<ActionDecision> arbitrate(List<ActionCandidate> candidates){
        var decisions=new LinkedHashMap<UUID,ActionDecision>();
        var byTarget=new TreeMap<String,List<ActionCandidate>>();
        for(ActionCandidate c:candidates.stream().sorted(ORDER).toList()){
            if(c.targetKey()==null) decisions.put(c.candidateId(),new ActionDecision(c,ActionDecision.Disposition.SELECTED,"INDEPENDENT_TARGET",null));
            else byTarget.computeIfAbsent(c.targetKey(),k->new ArrayList<>()).add(c);
        }
        for(var group:byTarget.values()){
            var classes=new LinkedHashMap<String,List<ActionCandidate>>();
            for(ActionCandidate c:group)classes.computeIfAbsent(c.equivalenceKey(),k->new ArrayList<>()).add(c);
            if(classes.size()==1){ select(classes.values().iterator().next(),decisions,"IDENTICAL_COMMAND_COALESCED");continue; }
            int best=group.getFirst().priority();
            var winners=classes.values().stream().filter(cls->cls.getFirst().priority()==best).toList();
            if(winners.size()>1){
                for(ActionCandidate c:group)decisions.put(c.candidateId(),new ActionDecision(c,ActionDecision.Disposition.SUPPRESSED_CONFLICT,"EQUAL_PRIORITY_CONFLICT",null));
                continue;
            }
            var winning=winners.getFirst();
            select(winning,decisions,"PRIORITY_WINNER");
            UUID primary=winning.getFirst().candidateId();
            for(ActionCandidate c:group)if(!decisions.containsKey(c.candidateId()))
                decisions.put(c.candidateId(),new ActionDecision(c,ActionDecision.Disposition.SUPPRESSED_PRIORITY,"LOWER_PRIORITY_CONFLICT",primary));
        }
        return candidates.stream().sorted(ORDER).map(c->decisions.get(c.candidateId())).toList();
    }

    private static void select(List<ActionCandidate> cls,Map<UUID,ActionDecision> decisions,String reason){
        ActionCandidate primary=cls.getFirst();
        decisions.put(primary.candidateId(),new ActionDecision(primary,ActionDecision.Disposition.SELECTED,reason,null));
        for(int i=1;i<cls.size();i++)decisions.put(cls.get(i).candidateId(),new ActionDecision(cls.get(i),ActionDecision.Disposition.COALESCED,"CONTRIBUTOR_OF_SELECTED",primary.candidateId()));
    }

    /** Canonical command target: device + binding/legacy capability + subtarget (relay channel). Null for non-command actions. */
    public static String targetKey(RuleAction action){
        if(!"SEND_COMMAND".equals(action.actionType())||action.parameters()==null)return null;
        var p=action.parameters();
        Object device=p.get("deviceId");
        Object capability=p.get("capabilityKey")!=null?p.get("capabilityKey"):p.get("capability");
        if(device==null||capability==null)return null;
        String channel="";
        if(p.get("value") instanceof Map<?,?> value&&value.get("channel") instanceof Number n)channel="channel:"+n.intValue();
        return device.toString().toLowerCase(Locale.ROOT)+"|"+capability.toString().trim().toLowerCase(Locale.ROOT)+"|"+channel;
    }

    /** Commands are identical only when command, value and unit all match. */
    public static String equivalenceKey(RuleAction action){
        var p=action.parameters()==null?Map.<String,Object>of():action.parameters();
        return String.valueOf(p.get("command"))+"|"+String.valueOf(p.get("value"))+"|"+String.valueOf(p.get("unit"));
    }
}
