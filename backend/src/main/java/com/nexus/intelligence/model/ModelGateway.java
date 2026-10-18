package com.nexus.intelligence.model;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The single place the platform asks a model anything. It checks, in order: the provider is configured (DISABLED), device evidence is only sent with the
 * separate explicit consent (NO_CONSENT), the assembled prompt fits (CONTEXT_TOO_LARGE), then runs the call under the guard and accepts the reply only as a
 * strict structured object of the requested schema (OUTPUT_TOO_LARGE / OUTPUT_INVALID). It returns a {@link ModelOutcome}; it never throws for a model
 * problem, never logs prompt or reply text, and has no dependency on commands, the Twin, rules or any store.
 */
public final class ModelGateway {
    private final ModelProvider provider;
    private final AiCallGuard guard;
    private final PromptAssembler assembler;
    private final GuardSettings settings;
    private final boolean sendDeviceEvidence;
    private final Map<String, AtomicLong> counters = new ConcurrentHashMap<>();

    public ModelGateway(ModelProvider provider, AiCallGuard guard, GuardSettings settings, boolean sendDeviceEvidence) {
        this(provider, guard, new PromptAssembler(settings.maxContextChars()), settings, sendDeviceEvidence);
    }

    ModelGateway(ModelProvider provider, AiCallGuard guard, PromptAssembler assembler, GuardSettings settings, boolean sendDeviceEvidence) {
        this.provider = provider;
        this.guard = guard;
        this.assembler = assembler;
        this.settings = settings;
        this.sendDeviceEvidence = sendDeviceEvidence;
    }

    public ModelOutcome ask(ModelRequest request) {
        ProviderIdentity identity = provider.identity();
        if (!provider.available()) return refuse(RefusalCode.DISABLED, null, identity);
        if (request.carriesDeviceEvidence() && !sendDeviceEvidence) return refuse(RefusalCode.NO_CONSENT, null, identity);
        String prompt;
        try {
            prompt = assembler.assemble(request);
        } catch (PromptAssembler.TooLarge e) {
            return refuse(RefusalCode.CONTEXT_TOO_LARGE, null, identity);
        }
        AiCallGuard.Result result = guard.call(() -> provider.complete(prompt, settings.maxOutputChars()));
        if (result.refusal() != null) return refuse(result.refusal(), null, identity);
        if (result.text() != null && result.text().length() > settings.maxOutputChars()) return refuse(RefusalCode.OUTPUT_TOO_LARGE, StructuredOutput.Code.TOO_LARGE, identity);
        try {
            StructuredOutput.Parsed parsed = StructuredOutput.parse(result.text(), request.schema(), settings.maxOutputChars());
            count("completed");
            return new ModelOutcome.Completed(parsed.values(), identity);
        } catch (StructuredOutput.Rejected e) {
            return refuse(RefusalCode.OUTPUT_INVALID, e.code(), identity);
        }
    }

    /** Closed-vocabulary counters: RefusalCode names and "completed". */
    public Map<String, Long> counters() {
        Map<String, Long> out = new java.util.TreeMap<>();
        counters.forEach((k, v) -> out.put(k, v.get()));
        return out;
    }

    private ModelOutcome refuse(RefusalCode code, StructuredOutput.Code detail, ProviderIdentity identity) {
        count(code.name());
        return new ModelOutcome.Refused(code, detail, identity);
    }

    private void count(String key) {
        counters.computeIfAbsent(key, k -> new AtomicLong()).incrementAndGet();
    }
}
