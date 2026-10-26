package com.nexus.intelligence.api;

import com.nexus.intelligence.draft.DraftOutcome;
import com.nexus.intelligence.trend.HardLimit;
import com.nexus.intelligence.trend.HardLimitDrafts;
import com.nexus.intelligence.trend.TrendService;
import com.nexus.rule.ai.ExplanationRateLimiter;
import com.nexus.shared.exception.ValidationException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;
import java.util.UUID;

/**
 * Trend analysis over HTTP (GET) and the hard-limit draft template (POST, stores a DRAFT only). Nothing here approves, activates, resumes or sends anything.
 * Unauthenticated like every NEXUS endpoint, so rate limited; the limiter establishes no identity.
 */
@RestController
@RequestMapping("/api/v1/intelligence")
public class TrendController {
    private final TrendService trends;
    private final HardLimitDrafts limits;
    private final ExplanationRateLimiter limiter;

    public TrendController(TrendService trends, HardLimitDrafts limits, ExplanationRateLimiter limiter) {
        this.trends = trends;
        this.limits = limits;
        this.limiter = limiter;
    }

    @GetMapping("/trends/devices/{deviceId}")
    public TrendService.Report trend(@PathVariable UUID deviceId, @RequestParam String capabilityKey, @RequestParam(required = false) Integer windowHours, @RequestParam(required = false) Double limitValue,
                                     @RequestParam(required = false) String limitUnit, @RequestParam(required = false) String limitComparison, @RequestParam(required = false) Double resolution,
                                     HttpServletRequest request) {
        if (!capabilityKey.matches("[a-z][a-z0-9_]{0,99}")) throw new ValidationException("capabilityKey is not a capability key");
        if (windowHours != null && (windowHours < 1 || windowHours > 720)) throw new ValidationException("windowHours must be 1 to 720");
        if (resolution != null && (!Double.isFinite(resolution) || resolution < 0)) throw new ValidationException("resolution must be a non-negative number");
        Optional<HardLimit> limit = Optional.empty();
        if (limitValue != null || limitUnit != null || limitComparison != null) {
            if (limitValue == null || limitUnit == null) throw new ValidationException("a limit needs limitValue and limitUnit");
            HardLimit.Comparison comparison;
            try {
                comparison = limitComparison == null ? HardLimit.Comparison.GREATER_OR_EQUAL : HardLimit.Comparison.valueOf(limitComparison);
            } catch (IllegalArgumentException unknown) {
                throw new ValidationException("limitComparison must be GREATER_OR_EQUAL or GREATER_THAN");
            }
            try {
                limit = Optional.of(new HardLimit(limitValue, limitUnit, comparison));
            } catch (IllegalArgumentException invalid) {
                throw new ValidationException(invalid.getMessage());
            }
        }
        limiter.acquire(request.getRemoteAddr());
        try {
            return trends.analyse(deviceId, capabilityKey, Optional.ofNullable(windowHours), limit, Optional.ofNullable(resolution));
        } finally {
            limiter.release();
        }
    }

    @PostMapping("/drafts/hard-limit")
    public ResponseEntity<DraftOutcome> hardLimit(@RequestBody HardLimitDrafts.Request body, HttpServletRequest request) {
        limiter.acquire(request.getRemoteAddr());
        try {
            DraftOutcome outcome;
            try {
                outcome = limits.propose(body);
            } catch (IllegalArgumentException invalid) {
                throw new ValidationException(invalid.getMessage());
            }
            return ResponseEntity.status(outcome instanceof DraftOutcome.Created ? HttpStatus.CREATED : HttpStatus.UNPROCESSABLE_ENTITY).body(outcome);
        } finally {
            limiter.release();
        }
    }
}
