package com.nexus.rule.timer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Bounded scanner over persisted due work; the clock decides, never JVM timers. */
@Component
public class RuleTimerWorker {
    private static final Logger log = LoggerFactory.getLogger(RuleTimerWorker.class);
    private final RuleTimerService timers;
    public RuleTimerWorker(RuleTimerService timers) { this.timers = timers; }

    @Scheduled(fixedDelayString = "${nexus.rule.timer-scan-ms:1000}")
    public void scan() {
        try { timers.processDue(100); } catch (RuntimeException failure) { log.warn("Rule timer scan unavailable: {}", failure.getMessage()); }
    }
}
