package com.nexus.rule.api;

import com.nexus.rule.api.dto.*;
import com.nexus.rule.application.*;
import com.nexus.rule.domain.Rule;
import com.nexus.rule.persistence.RuleVersionStore;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/v1/rules")
public class RuleLifecycleController {
    public record Mutation(Long expectedRevision) {}
    public record Draft(RuleRequest definition,Long expectedRevision) {}
    private final RuleLifecycleService lifecycle;
    private final RuleVersionStore versions;
    public RuleLifecycleController(RuleLifecycleService lifecycle,RuleVersionStore versions){this.lifecycle=lifecycle;this.versions=versions;}
    public static Long token(Long body,String header,boolean required) {
        Long supplied=null;
        if(header!=null)try{if(!header.matches("(?:\"[0-9]+\"|[0-9]+)"))throw new IllegalArgumentException();supplied=Long.valueOf(header.replace("\"",""));}
        catch(RuntimeException e){throw new RuleMutationException(400,"If-Match must be a strong numeric revision");}
        if(body!=null&&supplied!=null&&!body.equals(supplied))throw new RuleMutationException(400,"Conflicting revision tokens");
        Long token=supplied!=null?supplied:body;
        if(token==null&&required)throw new RuleMutationException(428,"Expected revision or If-Match is required");
        if(token!=null&&token<0)throw new RuleMutationException(400,"Revision must not be negative");
        return token;
    }
    private long checked(UUID id,Long body,String header) {
        Long token=token(body,header,true);
        if(header!=null&&versions.get(id).map(v->v.rowRevision()!=token).orElse(false))throw new RuleMutationException(412,"If-Match precondition failed");
        return token;
    }
    @PostMapping("/drafts")
    public RuleResponse createDraft(@RequestBody Draft request,@RequestHeader(value="If-Match",required=false)String header) {
        long token=token(request.expectedRevision(),header,true);
        if(token!=0)throw new RuleMutationException(header==null?409:412,"New draft requires initial revision 0");
        var d=request.definition();return RuleResponse.from(lifecycle.createDraft(new Rule(UUID.randomUUID(),d.name(),false,d.priority(),d.conditions(),d.actions(),d.metadata(),d.cooldownSeconds())));
    }
    @GetMapping("/{id}/versions")
    public List<Map<String,Object>> versions(@PathVariable UUID id) { return versions.history(id); }
    @PostMapping("/{id}/draft")
    public RuleResponse draft(@PathVariable UUID id,@RequestBody Draft request,@RequestHeader(value="If-Match",required=false)String header) {
        var d=request.definition();
        try{return RuleResponse.from(lifecycle.draft(new Rule(id,d.name(),false,d.priority(),d.conditions(),d.actions(),d.metadata(),d.cooldownSeconds()),checked(id,request.expectedRevision(),header)));}
        catch(RuleMutationException conflict){if(header!=null&&conflict.getStatusCode().value()==409&&conflict.getReason().startsWith("Stale rule revision"))throw new RuleMutationException(412,"If-Match precondition failed");throw conflict;}
    }
    @PostMapping("/{id}/lifecycle/{operation}")
    public org.springframework.http.ResponseEntity<RuleResponse> transition(@PathVariable UUID id,@PathVariable String operation,@RequestBody(required=false)Mutation request,@RequestHeader(value="If-Match",required=false)String header) {
        if(!Set.of("validate","activate","pause","resume","archive").contains(operation))throw new RuleMutationException(400,"Unknown lifecycle operation");
        com.nexus.rule.persistence.RuleVersionStore.View result;
        try{result=lifecycle.transition(id,operation,checked(id,request==null?null:request.expectedRevision(),header));}
        catch(RuleMutationException conflict){if(header!=null&&conflict.getStatusCode().value()==409&&conflict.getReason().startsWith("Stale rule revision"))throw new RuleMutationException(412,"If-Match precondition failed");throw conflict;}
        if(result!=null&&Set.of("validate","activate","resume").contains(operation)&&!result.diagnostics().isEmpty())throw new RuleDefinitionValidationException(result.diagnostics());
        return result==null?org.springframework.http.ResponseEntity.noContent().build():org.springframework.http.ResponseEntity.ok(RuleResponse.from(result));
    }
}
