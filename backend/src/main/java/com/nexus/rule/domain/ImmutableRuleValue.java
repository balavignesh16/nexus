package com.nexus.rule.domain;
import java.util.*;
/** Deep immutable JSON values without truthiness conversion or loss of null/ordering. */
final class ImmutableRuleValue {
    private ImmutableRuleValue() {}
    static Object freeze(Object value){
        if(value instanceof Map<?,?> map){var copy=new LinkedHashMap<Object,Object>();map.forEach((k,v)->copy.put(k,freeze(v)));return Collections.unmodifiableMap(copy);}
        if(value instanceof List<?> list){var copy=new ArrayList<>();for(Object item:list)copy.add(freeze(item));return Collections.unmodifiableList(copy);}
        return value;
    }
}
