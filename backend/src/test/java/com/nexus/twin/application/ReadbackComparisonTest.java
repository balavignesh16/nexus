package com.nexus.twin.application;

import com.nexus.device.capability.definition.CapabilityValueType;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ReadbackComparisonTest {
    private final ReadbackComparison comparison=new ReadbackComparison();

    @Test void exactAndNumericToleranceAreTyped() {
        assertTrue(comparison.supports("IDENTITY",null,"EXACT",CapabilityValueType.BOOLEAN,CapabilityValueType.BOOLEAN));
        assertTrue(comparison.matches("EXACT",CapabilityValueType.BOOLEAN,true,true,null));
        assertFalse(comparison.matches("EXACT",CapabilityValueType.BOOLEAN,true,false,null));
        assertTrue(comparison.matches("EXACT",CapabilityValueType.ENUM,"OPEN","OPEN",null));
        assertFalse(comparison.matches("EXACT",CapabilityValueType.ENUM,"OPEN","CLOSED",null));
        assertTrue(comparison.matches("EXACT",CapabilityValueType.STRING,"running","running",null));
        assertFalse(comparison.matches("EXACT",CapabilityValueType.STRING,"running","stopped",null));
        assertTrue(comparison.supports("IDENTITY",null,"NUMERIC_TOLERANCE",CapabilityValueType.NUMBER,CapabilityValueType.NUMBER));
        assertTrue(comparison.matches("NUMERIC_TOLERANCE",CapabilityValueType.NUMBER,new BigDecimal("10.0"),
                new BigDecimal("10.2"),new BigDecimal("0.2")));
        assertFalse(comparison.matches("NUMERIC_TOLERANCE",CapabilityValueType.NUMBER,10,10.3,new BigDecimal("0.2")));
    }

    @Test void objectRequiresExplicitSelectorAndDoesNotUseGenericEquality() {
        assertFalse(comparison.supports("IDENTITY",null,"EXACT",CapabilityValueType.OBJECT,CapabilityValueType.OBJECT));
        assertTrue(comparison.supports("OBJECT_FIELD","channel2","EXACT",CapabilityValueType.BOOLEAN,CapabilityValueType.OBJECT));
        assertEquals(true,comparison.select("OBJECT_FIELD","channel2",Map.of("channel1",false,"channel2",true)));
        assertEquals(false,comparison.select("OBJECT_FIELD","channel1",Map.of("channel1",false,"channel2",true)));
        comparison.registerComparator(new ReadbackComparator(){
            public String key(){return "OBJECT_STATE";}
            public boolean supports(CapabilityValueType type){return type==CapabilityValueType.OBJECT;}
            public boolean matches(CapabilityValueType type,Object desired,Object observed,BigDecimal tolerance){
                return desired instanceof Map<?,?> a && observed instanceof Map<?,?> b
                        && a.get("state").equals(b.get("state"));
            }
        });
        assertTrue(comparison.supports("IDENTITY",null,"OBJECT_STATE",CapabilityValueType.OBJECT,CapabilityValueType.OBJECT));
    }

    @Test void numericComparisonHasExactInsideBoundaryOutsideAndZeroToleranceCases() {
        assertTrue(comparison.matches("NUMERIC_TOLERANCE",CapabilityValueType.NUMBER,
                new BigDecimal("10.00"),new BigDecimal("10.0"),BigDecimal.ZERO));
        assertTrue(comparison.matches("NUMERIC_TOLERANCE",CapabilityValueType.NUMBER,
                new BigDecimal("10.00"),new BigDecimal("10.19"),new BigDecimal("0.20")));
        assertTrue(comparison.matches("NUMERIC_TOLERANCE",CapabilityValueType.NUMBER,
                new BigDecimal("10.00"),new BigDecimal("10.20"),new BigDecimal("0.20")));
        assertFalse(comparison.matches("NUMERIC_TOLERANCE",CapabilityValueType.NUMBER,
                new BigDecimal("10.00"),new BigDecimal("10.21"),new BigDecimal("0.20")));
        assertFalse(comparison.matches("NUMERIC_TOLERANCE",CapabilityValueType.NUMBER,
                new BigDecimal("10.00"),new BigDecimal("10.01"),BigDecimal.ZERO));
    }
}
