package com.nexus.rule.application;

import com.nexus.rule.domain.*;
import com.nexus.rule.persistence.RuleVersionStore;
import com.nexus.device.capability.definition.LegacyCapabilityDefinitionMapping;
import org.springframework.stereotype.Service;
import java.util.*;

/** Database-backed active executable references, never root draft JSON/cache. */
@Service
public class ActiveRuleReferenceGuard {
    private final RuleVersionStore versions;
    private final RuleCapabilityValidator validator;
    private final com.nexus.device.domain.DeviceRepository devices;
    public ActiveRuleReferenceGuard(RuleVersionStore versions,RuleCapabilityValidator validator,com.nexus.device.domain.DeviceRepository devices){this.versions=versions;this.validator=validator;this.devices=devices;}
    private boolean dependency(UUID device,String identifier,com.nexus.device.capability.CapabilityDirection direction,String key){
        if(identifier==null)return false;
        try{return validator.referenceDefinition(device,identifier,direction).map(d->d.getKey().equals(key)).orElse(false);}
        catch(org.springframework.dao.DataAccessException|jakarta.persistence.PersistenceException infrastructure){throw infrastructure;}
        catch(RuntimeException invalid){throw new RuleMutationException(409,"ACTIVE reference cannot be resolved safely: "+identifier);}
    }
    public boolean references(UUID device,String key){
        return versions.list(true).stream().anyMatch(v->{
            Rule rule=v.definition();
            var scopes=rule.conditions().stream().filter(c->c.field()==RuleField.DEVICE_ID).map(c->UUID.fromString(c.expectedValue())).toList();
            var affected=scopes.isEmpty()?(device==null?devices.findAll().stream().map(d->d.getId()).toList():List.of(device)):scopes;
            boolean condition=affected.stream().filter(id->device==null||device.equals(id)).anyMatch(id->key==null||rule.conditions().stream().anyMatch(c->dependency(id,c.capabilityKey()!=null?c.capabilityKey():c.capabilityType()==null?null:LegacyCapabilityDefinitionMapping.keyFor(c.capabilityType()),com.nexus.device.capability.CapabilityDirection.READ,key)));
            boolean action=rule.actions().stream().anyMatch(a->"SEND_COMMAND".equals(a.actionType())&&a.parameters()!=null
                && (device==null || device.equals(UUID.fromString(a.parameters().get("deviceId").toString())))
                && (key==null || dependency(UUID.fromString(a.parameters().get("deviceId").toString()),String.valueOf(a.parameters().getOrDefault("capabilityKey",a.parameters().get("capability"))),com.nexus.device.capability.CapabilityDirection.WRITE,key)));
            return condition||action;
        });
    }
    public void reject(UUID device,String key){if(references(device,key))throw new RuleMutationException(409,"ACTIVE immutable rule version references this execution contract");}
}
