package com.nexus.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.time.Clock;

@Configuration
public class TimeConfiguration {
    @Bean public Clock nexusClock(){return Clock.systemUTC();}
}
