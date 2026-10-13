package com.nexus.rule.application;

import com.nexus.action.application.ActionExecutor;
import com.nexus.action.domain.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import java.util.*;

/** Current stub executors retain their behavior; owner gate is not a check-then-act gap. */
@Service
public class RuleNonCommandExecutionService {
    private final JdbcTemplate jdbc;
    private final RuleExecutionAuthorization authorization;
    public RuleNonCommandExecutionService(JdbcTemplate jdbc,RuleExecutionAuthorization authorization){this.jdbc=jdbc;this.authorization=authorization;}
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public ActionExecutionResult execute(ActionExecutionRequest request,ActionContext context,ActionExecutor executor){
        Set<UUID> devices=new TreeSet<>(Comparator.comparing(UUID::toString));
        if(context!=null&&context.triggeringEvent()!=null&&context.triggeringEvent().deviceId()!=null)devices.add(context.triggeringEvent().deviceId());
        if(request.parameters()!=null&&request.parameters().get("deviceId")!=null)devices.add(UUID.fromString(request.parameters().get("deviceId").toString()));
        for(UUID id:devices)if(jdbc.queryForList("SELECT id FROM devices WHERE id=? FOR UPDATE",id).isEmpty())throw new RuleExecutionSuppressedException("TARGET_DEVICE_UNAVAILABLE");
        authorization.check(request,context);
        return executor.execute(request,context);
    }
}
