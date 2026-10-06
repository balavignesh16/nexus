package com.nexus.twin.application;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FreshnessPolicyTest {
    private final FreshnessPolicy policy=new FreshnessPolicy();
    @Test void inheritsTemplateAndSystemDefaults(){assertEquals(30,policy.effective(null,30));assertEquals(120,policy.effective(null,null));}
    @Test void acceptsNarrowing(){assertEquals(10,policy.effective(10,30));}
    @Test void rejectsZeroNegativeAndExpansion(){assertThrows(IllegalArgumentException.class,()->policy.effective(0,30));assertThrows(IllegalArgumentException.class,()->policy.effective(-1,30));assertThrows(IllegalArgumentException.class,()->policy.effective(31,30));}
}
