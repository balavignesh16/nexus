package com.nexus.twin.application;

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import java.time.Duration;

@Component
public class FreshnessPolicy {
    public static final int SYSTEM_DEFAULT_SECONDS=120;
    private final int systemDefaultSeconds;

    public FreshnessPolicy(){this(Duration.ofSeconds(SYSTEM_DEFAULT_SECONDS));}

    @Autowired
    public FreshnessPolicy(@Value("${nexus.twin.default-freshness-threshold:PT120S}") Duration systemDefault){
        if(systemDefault==null||systemDefault.isZero()||systemDefault.isNegative()
                ||systemDefault.getSeconds()>Integer.MAX_VALUE||systemDefault.getNano()!=0)
            throw new IllegalArgumentException("Default freshness threshold must be a positive whole number of seconds");
        this.systemDefaultSeconds=(int)systemDefault.getSeconds();
    }

    public int systemDefaultSeconds(){return systemDefaultSeconds;}

    public int effective(Integer bindingOverride,Integer templateThreshold){
        validateOverride(bindingOverride,templateThreshold);
        return bindingOverride!=null?bindingOverride:templateThreshold!=null?templateThreshold:systemDefaultSeconds;
    }

    public void validateOverride(Integer override,Integer templateThreshold){
        if(override!=null&&override<=0)throw new IllegalArgumentException("Freshness override must be positive");
        int ceiling=templateThreshold==null?systemDefaultSeconds:templateThreshold;
        if(override!=null&&override>ceiling)
            throw new IllegalArgumentException("Freshness override may only narrow the parent threshold of "+ceiling+" seconds");
    }
}
