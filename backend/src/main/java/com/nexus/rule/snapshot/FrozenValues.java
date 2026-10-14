package com.nexus.rule.snapshot;

import java.util.*;

/** Deep immutable copy of JSON-like values; never converts by truthiness (false, 0 and "" are preserved). */
public final class FrozenValues {
    private FrozenValues(){}
    public static Object freeze(Object value){
        if(value instanceof Map<?,?> map){var copy=new LinkedHashMap<Object,Object>();map.forEach((k,v)->copy.put(k,freeze(v)));return Collections.unmodifiableMap(copy);}
        if(value instanceof List<?> list){var copy=new ArrayList<>();for(Object item:list)copy.add(freeze(item));return Collections.unmodifiableList(copy);}
        return value;
    }
}
