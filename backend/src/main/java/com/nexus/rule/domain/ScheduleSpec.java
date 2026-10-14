package com.nexus.rule.domain;

import java.time.Instant;

/** C18 initial schedules: UTC one-shot or fixed interval. No cron, timezone or DST. */
public record ScheduleSpec(Kind kind, Instant at, Long intervalSeconds, Instant startsAt) {
    public enum Kind { ONE_SHOT, FIXED_INTERVAL }
}
