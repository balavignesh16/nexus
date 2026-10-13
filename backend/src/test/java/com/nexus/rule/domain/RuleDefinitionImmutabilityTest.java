package com.nexus.rule.domain;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class RuleDefinitionImmutabilityTest {
    @Test void nestedExecutableValuesAreCopiedAndFrozenWithoutTruthinessLoss(){
        var nested=new LinkedHashMap<String,Object>();nested.put("false",false);nested.put("zero",0);nested.put("empty","");
        var parameters=new LinkedHashMap<String,Object>();parameters.put("value",nested);parameters.put("items",new ArrayList<>(List.of(1,2)));
        var action=new RuleAction("WEBHOOK",parameters);nested.put("zero",99);parameters.put("value","changed");
        assertEquals(Map.of("false",false,"zero",0,"empty",""),action.parameters().get("value"));
        assertThrows(UnsupportedOperationException.class,()->action.parameters().put("value",false));
        @SuppressWarnings("unchecked") var frozen=(Map<String,Object>)action.parameters().get("value");assertThrows(UnsupportedOperationException.class,()->frozen.put("zero",99));
    }
    @Test void sourceCollectionsCannotChangeCapturedDefinitionOrExpectedStrings(){
        var conditions=new ArrayList<>(List.of(new RuleCondition(RuleField.VALUE,RuleOperator.EQ,"0")));var actions=new ArrayList<>(List.of(new RuleAction("CREATE_ALERT",Map.of())));var metadata=new LinkedHashMap<String,String>();metadata.put("owner","old");
        var rule=new Rule(UUID.randomUUID(),"Pinned",true,1,conditions,actions,metadata);
        conditions.clear();actions.clear();metadata.put("owner","new");
        assertEquals("0",rule.conditions().getFirst().expectedValue());assertEquals(1,rule.actions().size());assertEquals("old",rule.metadata().get("owner"));
    }
}
