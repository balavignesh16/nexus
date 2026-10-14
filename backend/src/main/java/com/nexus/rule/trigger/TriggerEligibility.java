package com.nexus.rule.trigger;

import com.nexus.rule.domain.Rule;
import com.nexus.rule.domain.TriggerSubscription;
import com.nexus.rule.domain.TriggerType;

import java.util.Set;

/** Explicit immutable-version subscription test. Imported rules subscribe to the legacy adapter only. */
public final class TriggerEligibility {
    private TriggerEligibility(){}

    /** MANUAL, SCHEDULE and TEMPORAL_DUE facts address one rule version; they are never broadcast to other rules. */
    public static boolean targeted(TriggerType type){return type==TriggerType.MANUAL||type==TriggerType.SCHEDULE||type==TriggerType.TEMPORAL_DUE;}

    static boolean addressedTo(Rule rule,TriggerObservation observation){
        Object ruleId=observation.attributes().get("ruleId"),version=observation.attributes().get("ruleVersionId");
        var identity=rule.executionIdentity();
        return ruleId!=null&&ruleId.toString().equals(rule.ruleId().toString())
            &&(identity==null||version!=null&&version.toString().equals(identity.ruleVersionId().toString()));
    }

    /** Filter keys a subscription may carry, per trigger type; anything else is a validation error. */
    public static Set<String> allowedFilters(TriggerType type){
        return switch(type){
            case ACCEPTED_TELEMETRY,TWIN_STATE_CHANGED->Set.of("capabilityKey");
            case CONNECTIVITY_CHANGED->Set.of("connectivity");
            case VERIFICATION_CHANGED->Set.of("status");
            case RULE_MATCHED->Set.of("parentRuleId");
            default->Set.of();
        };
    }

    public static boolean eligible(Rule rule,TriggerObservation observation){
        for(TriggerSubscription s:rule.subscriptions()){
            // VERIFICATION_TIMED_OUT is the named TIMED_OUT form of the same transition identity, never a second occurrence.
            boolean timedOutForm=s.type()==TriggerType.VERIFICATION_TIMED_OUT&&observation.type()==TriggerType.VERIFICATION_CHANGED
                &&"TIMED_OUT".equals(String.valueOf(observation.attributes().get("status")));
            if(s.type()!=observation.type()&&!timedOutForm)continue;
            if(observation.type()==TriggerType.LEGACY_DOMAIN_EVENT)return true;
            if(targeted(observation.type())&&!addressedTo(rule,observation))continue;
            boolean all=true;
            for(var filter:s.filters().entrySet()){
                Object actual=observation.attributes().get(filter.getKey());
                if(actual==null||!actual.toString().equalsIgnoreCase(filter.getValue())){all=false;break;}
            }
            if(all)return true;
        }
        return false;
    }
}
