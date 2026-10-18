package com.nexus.adapter.http;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;

/**
 * Settings of the HTTP/webhook adapter. The adapter exists only when {@code nexus.adapter.http.enabled=true} (the default is off, which is the
 * rollback: the application behaves as before V41). Every value is validated at startup, so a bad setting stops the application instead of
 * weakening a guard silently:
 * <ul>
 * <li>the device-key pepper must be present (checked by {@link HttpWebhookAdapter});</li>
 * <li>with credentials enabled the server must be bound to a loopback address unless
 *     {@code nexus.adapter.http.require-forwarded-https=true}, in which case every request must carry {@code X-Forwarded-Proto: https};</li>
 * <li>the contact-eligibility window is 15 to 300 seconds (initial value 60), the body limit 1 KiB to 64 KiB (8 KiB), the per-device concurrency 1 to 16 (2).</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "nexus.adapter.http.enabled", havingValue = "true")
public class HttpAdapterSettings {
    private final int maxBodyBytes;
    private final int perDeviceConcurrency;
    private final Duration contactEligibilityWindow;
    private final Duration contactWriteInterval;
    private final boolean requireForwardedHttps;

    public HttpAdapterSettings(@Value("${nexus.adapter.http.max-body-bytes:8192}") int maxBodyBytes,
                               @Value("${nexus.adapter.http.per-device-concurrency:2}") int perDeviceConcurrency,
                               @Value("${nexus.adapter.http.contact-eligibility-window:PT60S}") Duration contactEligibilityWindow,
                               @Value("${nexus.adapter.http.contact-write-interval:PT5S}") Duration contactWriteInterval,
                               @Value("${nexus.adapter.http.require-forwarded-https:false}") boolean requireForwardedHttps,
                               @Value("${server.address:}") String serverAddress) {
        if (maxBodyBytes < 1024 || maxBodyBytes > 65536) throw new IllegalStateException("nexus.adapter.http.max-body-bytes must be 1024 to 65536");
        if (perDeviceConcurrency < 1 || perDeviceConcurrency > 16) throw new IllegalStateException("nexus.adapter.http.per-device-concurrency must be 1 to 16");
        if (contactEligibilityWindow.compareTo(Duration.ofSeconds(15)) < 0 || contactEligibilityWindow.compareTo(Duration.ofSeconds(300)) > 0) {
            throw new IllegalStateException("nexus.adapter.http.contact-eligibility-window must be between 15 and 300 seconds");
        }
        if (contactWriteInterval.isNegative() || contactWriteInterval.compareTo(Duration.ofMinutes(1)) > 0) {
            throw new IllegalStateException("nexus.adapter.http.contact-write-interval must be between 0 and 60 seconds");
        }
        if (!requireForwardedHttps && !isLoopback(serverAddress)) {
            throw new IllegalStateException("refusing to start the HTTP adapter with device credentials on a non-loopback bind address ("
                    + (serverAddress == null || serverAddress.isBlank() ? "all interfaces" : "a non-loopback address")
                    + "): bind to 127.0.0.1 behind a TLS-terminating proxy, or set nexus.adapter.http.require-forwarded-https=true");
        }
        this.maxBodyBytes = maxBodyBytes;
        this.perDeviceConcurrency = perDeviceConcurrency;
        this.contactEligibilityWindow = contactEligibilityWindow;
        this.contactWriteInterval = contactWriteInterval;
        this.requireForwardedHttps = requireForwardedHttps;
    }

    static boolean isLoopback(String address) {
        if (address == null || address.isBlank()) return false;
        try {
            return InetAddress.getByName(address.strip()).isLoopbackAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }

    public int maxBodyBytes() { return maxBodyBytes; }
    public int perDeviceConcurrency() { return perDeviceConcurrency; }
    public Duration contactEligibilityWindow() { return contactEligibilityWindow; }
    public Duration contactWriteInterval() { return contactWriteInterval; }
    public boolean requireForwardedHttps() { return requireForwardedHttps; }
}
