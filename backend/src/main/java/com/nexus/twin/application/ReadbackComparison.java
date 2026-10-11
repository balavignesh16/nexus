package com.nexus.twin.application;

import com.nexus.device.capability.definition.CapabilityValueType;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.util.*;

/** Typed strategy registry. OBJECT comparison requires an explicitly registered strategy. */
@Component
public class ReadbackComparison {
    private final Map<String,ReadbackSelector> selectors=new HashMap<>();
    private final Map<String,ReadbackComparator> comparators=new HashMap<>();

    public ReadbackComparison(){
        registerSelector(new ReadbackSelector(){
            public String key(){return "IDENTITY";}
            public boolean supports(CapabilityValueType type,String path){return path==null||path.isBlank();}
            public Object select(Object reported,String path){return reported;}
        });
        registerSelector(new ReadbackSelector(){
            public String key(){return "OBJECT_FIELD";}
            public boolean supports(CapabilityValueType type,String path){
                return type==CapabilityValueType.OBJECT&&path!=null&&path.matches("[A-Za-z][A-Za-z0-9_]*");
            }
            public Object select(Object reported,String path){
                return reported instanceof Map<?,?> map?map.get(path):null;
            }
        });
        registerComparator(new ReadbackComparator(){
            public String key(){return "EXACT";}
            public boolean supports(CapabilityValueType type){return type!=CapabilityValueType.NUMBER&&type!=CapabilityValueType.OBJECT;}
            public boolean matches(CapabilityValueType type,Object desired,Object readback,BigDecimal tolerance){
                return desired!=null&&desired.equals(readback);
            }
        });
        registerComparator(new ReadbackComparator(){
            public String key(){return "NUMERIC_TOLERANCE";}
            public boolean supports(CapabilityValueType type){return type==CapabilityValueType.NUMBER;}
            public boolean matches(CapabilityValueType type,Object desired,Object readback,BigDecimal tolerance){
                if(!(desired instanceof Number)||!(readback instanceof Number))return false;
                return new BigDecimal(desired.toString()).subtract(new BigDecimal(readback.toString()))
                        .abs().compareTo(tolerance==null?BigDecimal.ZERO:tolerance)<=0;
            }
        });
    }

    public void registerSelector(ReadbackSelector selector){
        if(selectors.putIfAbsent(selector.key(),selector)!=null)throw new IllegalArgumentException("Duplicate selector: "+selector.key());
    }
    public void registerComparator(ReadbackComparator comparator){
        if(comparators.putIfAbsent(comparator.key(),comparator)!=null)throw new IllegalArgumentException("Duplicate comparator: "+comparator.key());
    }

    public boolean supports(String selector,String path,String comparator,CapabilityValueType desiredType,
            CapabilityValueType reportedType) {
        var selection=selectors.get(selector);var compare=comparators.get(comparator);
        if(selection==null||compare==null||desiredType==null||reportedType==null)return false;
        if(!selection.supports(reportedType,path)||!compare.supports(desiredType))return false;
        return !"IDENTITY".equals(selector)||reportedType==desiredType;
    }

    public Object select(String selector,String path,Object reported) {
        var strategy=selectors.get(selector);
        return strategy==null?null:strategy.select(reported,path);
    }

    public boolean matches(String comparator,CapabilityValueType type,Object desired,Object reported,BigDecimal tolerance) {
        var strategy=comparators.get(comparator);
        return strategy!=null&&strategy.supports(type)&&strategy.matches(type,desired,reported,tolerance);
    }
}
