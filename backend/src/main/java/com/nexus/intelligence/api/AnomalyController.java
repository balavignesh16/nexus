package com.nexus.intelligence.api;

import com.nexus.intelligence.anomaly.AnomalyReport;
import com.nexus.intelligence.anomaly.AnomalyService;
import com.nexus.intelligence.recommend.Recommendation;
import com.nexus.intelligence.recommend.RecommendationNarrator;
import com.nexus.intelligence.recommend.RecommendationService;
import com.nexus.rule.ai.ExplanationRateLimiter;
import com.nexus.shared.exception.ResourceNotFoundException;
import com.nexus.shared.exception.ValidationException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;
import java.util.UUID;

/**
 * Layer 3 over HTTP: GET only. Anomaly findings and recommendations are computed on request from persisted facts inside a read-only transaction; nothing here
 * writes, publishes, edits a rule, approves a draft or changes a device. Unauthenticated like every NEXUS endpoint, so rate limited with the same limiter as
 * the other intelligence routes; the limiter establishes no identity. {@code explain=true} adds an OPTIONAL model reading order and summary that must pass
 * the P6.2 gateway and a grounding check; the deterministic result is unchanged by it.
 */
@RestController
@RequestMapping("/api/v1/intelligence")
public class AnomalyController {
    public record RecommendationsResponse(RecommendationService.Report report, RecommendationNarrator.Narration explanation) {}

    private final AnomalyService anomalies;
    private final RecommendationService recommendations;
    private final RecommendationNarrator narrator;
    private final ExplanationRateLimiter limiter;

    public AnomalyController(AnomalyService anomalies, RecommendationService recommendations, RecommendationNarrator narrator, ExplanationRateLimiter limiter) {
        this.anomalies = anomalies;
        this.recommendations = recommendations;
        this.narrator = narrator;
        this.limiter = limiter;
    }

    @GetMapping("/anomalies/devices/{deviceId}")
    public AnomalyReport anomalies(@PathVariable UUID deviceId, @RequestParam(required = false) String capabilityKey, @RequestParam(required = false) Integer windowHours,
                                   @RequestParam(required = false) Integer recentMinutes, HttpServletRequest request) {
        if (capabilityKey != null && !capabilityKey.matches("[a-z][a-z0-9_]{0,99}")) throw new ValidationException("capabilityKey is not a capability key");
        windows(windowHours, recentMinutes);
        if (recentMinutes != null && recentMinutes > (windowHours == null ? anomalies.settings().windowHours() : windowHours) * 30) throw new ValidationException("recentMinutes may be at most half the window");
        limiter.acquire(request.getRemoteAddr());
        try {
            AnomalyReport report = anomalies.analyse(deviceId, Optional.ofNullable(capabilityKey), Optional.ofNullable(windowHours), Optional.ofNullable(recentMinutes));
            if (!report.deviceFound()) throw new ResourceNotFoundException("Device", deviceId.toString());
            return report;
        } finally {
            limiter.release();
        }
    }

    @GetMapping("/recommendations")
    public RecommendationsResponse recommendations(@RequestParam(required = false) UUID deviceId, @RequestParam(required = false) UUID ruleId, @RequestParam(required = false) Integer windowHours,
                                                   @RequestParam(required = false) Integer telemetryHours, @RequestParam(defaultValue = "false") boolean explain, HttpServletRequest request) {
        if (deviceId != null && ruleId != null) throw new ValidationException("give a deviceId or a ruleId, not both");
        windows(windowHours, null);
        windows(telemetryHours, null);
        limiter.acquire(request.getRemoteAddr());
        try {
            RecommendationService.Report report = recommendations.recommend(Optional.ofNullable(deviceId), Optional.ofNullable(ruleId), Optional.ofNullable(windowHours), Optional.ofNullable(telemetryHours));
            if (!report.scopeFound()) throw new ResourceNotFoundException("Device", String.valueOf(deviceId));
            RecommendationNarrator.Narration narration = explain ? narrator.narrate(report.recommendations().stream().filter(r -> r.status() == Recommendation.Status.RECOMMENDED).toList()) : null;
            return new RecommendationsResponse(report, narration);
        } finally {
            limiter.release();
        }
    }

    private static void windows(Integer hours, Integer recentMinutes) {
        if (hours != null && (hours < 1 || hours > 720)) throw new ValidationException("window hours are 1 to 720");
        if (recentMinutes != null && (recentMinutes < 5 || recentMinutes > 21_600)) throw new ValidationException("recentMinutes is 5 to 21600");
    }
}
