package com.nexus.twin.domain;

import java.time.Instant;

public record TwinStateQualityEvidence(TwinStateQuality state,String reason,Instant evaluatedAt,
        Instant receivedAt,int thresholdSeconds,String connectivity) {}
