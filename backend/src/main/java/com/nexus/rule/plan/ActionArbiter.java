package com.nexus.rule.plan;

import com.nexus.rule.domain.RuleAction;
import java.util.*;

/**
 * Deterministic C16 arbitration over one trigger batch. Same target + identical command: coalesce and keep all
 * contributors; conflicting: smaller numeric priority wins; equal-priority conflict: suppress every conflicting
 * candidate. Ordering uses priority, rule id, revision and ordinal, never repository iteration order.
 * Mutual exclusion (C16) then applies to the surviving COMMAND candidates: within an explicit group at most one rule may act; the
 * smaller configured rank wins, an equal best rank suppresses every competing command. Safety overrides and approval do not exist here.
 */
public final class ActionArbiter {
    private ActionArbiter(){}

    public static final Comparator<ActionCandidate> ORDER=Comparator.comparingInt(ActionCandidate::priority)
        .thenComparing(c->c.rule().ruleId().toString()).thenComparingLong(ActionCandidate::revision).thenComparingInt(ActionCandidate::ordinal);

    public static List<ActionDecision> arbitrate(List<ActionCandidate> candidates){return arbitrate(candidates,List.of());}

    public static List<ActionDecision> arbitrate(List<ActionCandidate> candidates,List<ExclusionMembership> memberships){
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
        if(!memberships.isEmpty())applyExclusion(decisions,memberships);
        return candidates.stream().sorted(ORDER).map(c->decisions.get(c.candidateId())).toList();
    }

    /** Why a command candidate is excluded; compared under a total order so merging several groups never depends on their ids. */
    private record Cause(boolean conflict, int winnerRank, String winnerRule) implements Comparable<Cause> {
        public int compareTo(Cause other) {
            int byKind = Boolean.compare(other.conflict, conflict);               // an equal-rank conflict (fail closed) outranks a lower-rank loss
            if (byKind != 0) return byKind;
            int byRank = Integer.compare(winnerRank, other.winnerRank);
            return byRank != 0 ? byRank : winnerRule.compareTo(other.winnerRule);
        }
    }

    private static boolean commandSurvivor(ActionDecision d) {
        return d.candidate().targetKey() != null && (d.disposition() == ActionDecision.Disposition.SELECTED || d.disposition() == ActionDecision.Disposition.COALESCED);
    }
    private static String classKey(ActionCandidate c) { return c.targetKey() + "#" + c.equivalenceKey(); }

    /**
     * Mutual exclusion (C16) over the command candidates that survived target arbitration. Every group is judged against the SAME initial
     * candidate set - never against what another group already removed - and a candidate excluded by several groups keeps the strongest
     * cause under a total order, so the outcome cannot depend on group ids, membership order or candidate order.
     * The rule is conservative: an excluded rule can still exclude others in a different group.
     * After the exclusions each identical-command class is rebuilt (a surviving contributor is promoted when its primary was removed),
     * and every winner pointer is then resolved to the FINAL selected primary - or left null with a truthful reason when the winner
     * itself ended up excluded.
     */
    private static void applyExclusion(Map<UUID,ActionDecision> decisions,List<ExclusionMembership> memberships){
        var initial=decisions.values().stream().filter(ActionArbiter::commandSurvivor).sorted(Comparator.comparing(ActionDecision::candidate,ORDER)).toList();
        var groups=new HashMap<UUID,Map<UUID,Integer>>();
        for(var m:memberships)groups.computeIfAbsent(m.groupId(),g->new HashMap<>()).put(m.ruleId(),m.rank());
        var causes=new HashMap<UUID,Cause>();
        for(var ranks:groups.values()){
            var inGroup=initial.stream().filter(d->ranks.containsKey(d.candidate().rule().ruleId())).toList();
            var rules=new TreeSet<String>();
            inGroup.forEach(d->rules.add(d.candidate().rule().ruleId().toString()));
            if(rules.size()<2)continue;
            int best=rules.stream().mapToInt(r->ranks.get(UUID.fromString(r))).min().orElseThrow();
            var winners=rules.stream().filter(r->ranks.get(UUID.fromString(r))==best).toList();
            for(var d:inGroup){
                String rule=d.candidate().rule().ruleId().toString();
                Cause cause=winners.size()>1?new Cause(true,best,""):winners.getFirst().equals(rule)?null:new Cause(false,best,winners.getFirst());
                if(cause!=null)causes.merge(d.candidate().candidateId(),cause,(x,y)->x.compareTo(y)<=0?x:y);
            }
        }
        if(causes.isEmpty())return;
        for(var d:initial){
            var cause=causes.get(d.candidate().candidateId());
            if(cause!=null)decisions.put(d.candidate().candidateId(),new ActionDecision(d.candidate(),
                cause.conflict()?ActionDecision.Disposition.SUPPRESSED_EXCLUSION_CONFLICT:ActionDecision.Disposition.SUPPRESSED_EXCLUSION,
                cause.conflict()?"EXCLUSION_EQUAL_RANK_CONFLICT":"EXCLUSION_LOWER_RANK",null));
        }
        var classes=new TreeMap<String,List<ActionDecision>>();
        for(var d:decisions.values())if(commandSurvivor(d))classes.computeIfAbsent(classKey(d.candidate()),k->new ArrayList<>()).add(d);
        var finalPrimary=new HashMap<String,UUID>();
        for(var entry:classes.entrySet()){
            var cls=entry.getValue();
            cls.sort(Comparator.comparing(ActionDecision::candidate,ORDER));
            var primary=cls.getFirst().candidate();
            finalPrimary.put(entry.getKey(),primary.candidateId());
            if(cls.getFirst().disposition()!=ActionDecision.Disposition.SELECTED)
                decisions.put(primary.candidateId(),new ActionDecision(primary,ActionDecision.Disposition.SELECTED,"PROMOTED_AFTER_EXCLUSION",null));
            for(int i=1;i<cls.size();i++)
                decisions.put(cls.get(i).candidate().candidateId(),new ActionDecision(cls.get(i).candidate(),ActionDecision.Disposition.COALESCED,"CONTRIBUTOR_OF_SELECTED",primary.candidateId()));
        }
        // Winner pointers of exclusion losses: the winning rule's first surviving candidate, resolved to its class's final primary.
        for(var d:initial){
            var cause=causes.get(d.candidate().candidateId());
            if(cause==null||cause.conflict())continue;
            var winner=initial.stream().filter(w->w.candidate().rule().ruleId().toString().equals(cause.winnerRule())&&!causes.containsKey(w.candidate().candidateId())).findFirst();
            UUID pointer=winner.map(w->finalPrimary.get(classKey(w.candidate()))).orElse(null);
            decisions.put(d.candidate().candidateId(),new ActionDecision(d.candidate(),ActionDecision.Disposition.SUPPRESSED_EXCLUSION,
                pointer==null?"EXCLUSION_LOWER_RANK_WINNER_EXCLUDED":"EXCLUSION_LOWER_RANK",pointer));
        }
        // Priority losses named the primary of the class that beat them; resolve that to the class's final primary, or null if none acts.
        var byId=new HashMap<UUID,ActionCandidate>();
        decisions.values().forEach(d->byId.put(d.candidate().candidateId(),d.candidate()));
        for(var d:new ArrayList<>(decisions.values())){
            if(d.disposition()!=ActionDecision.Disposition.SUPPRESSED_PRIORITY||d.winnerCandidateId()==null)continue;
            var winnerDecision=decisions.get(d.winnerCandidateId());
            if(winnerDecision!=null&&winnerDecision.disposition()==ActionDecision.Disposition.SELECTED)continue;
            UUID pointer=finalPrimary.get(classKey(byId.get(d.winnerCandidateId())));
            decisions.put(d.candidate().candidateId(),new ActionDecision(d.candidate(),ActionDecision.Disposition.SUPPRESSED_PRIORITY,
                pointer==null?"LOWER_PRIORITY_CONFLICT_WINNER_EXCLUDED":"LOWER_PRIORITY_CONFLICT",pointer));
        }
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
