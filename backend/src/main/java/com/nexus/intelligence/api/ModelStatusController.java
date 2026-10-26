package com.nexus.intelligence.api;

import com.nexus.intelligence.model.ModelGateway;
import com.nexus.intelligence.model.OllamaProbe;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Local assistant diagnostics only: no prompts, secrets, inference or configuration mutation. */
@RestController
@ConditionalOnProperty(prefix = "nexus.assistant", name = "enabled", havingValue = "true")
public class ModelStatusController {
    private final ModelGateway gateway;
    private final boolean ollama;
    private final String baseUrl;
    private final String model;
    private Probe cached;
    private long nextProbe;
    // A stalled metadata body must not occupy request threads indefinitely or spawn unbounded probes.
    private final ThreadPoolExecutor probes = new ThreadPoolExecutor(0, 1, 10, TimeUnit.SECONDS,
            new SynchronousQueue<>(), task -> {
                Thread thread = new Thread(task, "ollama-status-probe");
                thread.setDaemon(true);
                return thread;
            });

    public record Probe(OllamaProbe.Inspection inspection, Boolean loaded, Instant checkedAt) {}
    public record Status(String mode, ModelGateway.RuntimeStatus gateway, Probe ollama) {}

    public ModelStatusController(ModelGateway gateway,
            @Value("${nexus.ai.ollama.enabled:false}") boolean ollama,
            @Value("${nexus.ai.ollama.base-url:http://localhost:11434}") String baseUrl,
            @Value("${nexus.ai.ollama.model:llama3.2:3b}") String model) {
        this.gateway = gateway;
        this.ollama = ollama;
        this.baseUrl = baseUrl;
        this.model = model;
    }

    @GetMapping("/api/v1/assistant/model-status")
    public Status status() {
        return new Status(ollama ? "OLLAMA_LOCAL" : gateway.runtimeStatus().configured() ? "OTHER_PROVIDER" : "DETERMINISTIC_ONLY",
                gateway.runtimeStatus(), ollama ? probe() : null);
    }

    private synchronized Probe probe() {
        if (cached == null || System.nanoTime() >= nextProbe) {
            java.util.concurrent.Future<Probe> pending = null;
            try {
                pending = probes.submit(() -> {
                    var inspection = OllamaProbe.inspect(baseUrl, model, Duration.ofSeconds(2));
                    Boolean loaded = inspection.reachable() ? OllamaProbe.loaded(baseUrl, model, Duration.ofSeconds(2)) : null;
                    return new Probe(inspection, loaded, Instant.now());
                });
                cached = pending.get(9, TimeUnit.SECONDS);
            } catch (Exception unavailable) {
                if (pending != null) pending.cancel(true);
                if (unavailable instanceof InterruptedException) Thread.currentThread().interrupt();
                cached = new Probe(new OllamaProbe.Inspection(false, null, false, null, null, null, null, null,
                        List.of(), false, false, "Metadata probe unavailable or timed out; model state is unverified."), null, Instant.now());
            }
            nextProbe = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        }
        return cached;
    }

    @jakarta.annotation.PreDestroy
    public void close() { probes.shutdownNow(); }
}
