package com.nexus.intelligence.api;

import com.nexus.intelligence.energy.EnergyService;
import com.nexus.rule.ai.ExplanationRateLimiter;
import com.nexus.shared.exception.ValidationException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;
import java.util.UUID;

/**
 * Energy and usage from stored power or energy readings, GET only. A read-only report: nothing is written, sent or estimated from ratings. Unauthenticated like every
 * NEXUS endpoint, so rate limited; the limiter establishes no identity.
 */
@RestController
@RequestMapping("/api/v1/intelligence")
public class EnergyController {
    private final EnergyService energy;
    private final ExplanationRateLimiter limiter;

    public EnergyController(EnergyService energy, ExplanationRateLimiter limiter) {
        this.energy = energy;
        this.limiter = limiter;
    }

    @GetMapping("/energy")
    public EnergyService.Report energy(@RequestParam(required = false) String deviceId, @RequestParam(required = false) Integer windowHours, HttpServletRequest request) {
        UUID device = null;
        if (deviceId != null) {
            try {
                device = UUID.fromString(deviceId);
            } catch (IllegalArgumentException invalid) {
                throw new ValidationException("deviceId is not a UUID");
            }
        }
        if (windowHours != null && (windowHours < 1 || windowHours > 720)) throw new ValidationException("windowHours must be 1 to 720");
        limiter.acquire(request.getRemoteAddr());
        try {
            return energy.analyse(Optional.ofNullable(device), Optional.ofNullable(windowHours));
        } finally {
            limiter.release();
        }
    }
}
