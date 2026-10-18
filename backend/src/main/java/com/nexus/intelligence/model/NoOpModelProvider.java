package com.nexus.intelligence.model;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** The default: no model. Every AI feature then uses its deterministic implementation and nothing leaves the process. */
@Component
@ConditionalOnProperty(name = "nexus.ai.enabled", havingValue = "false", matchIfMissing = true)
public class NoOpModelProvider implements ModelProvider {
    @Override
    public ProviderIdentity identity() {
        return ProviderIdentity.NONE;
    }

    @Override
    public boolean available() {
        return false;
    }

    @Override
    public String complete(String prompt, int maxOutputChars) throws ModelProviderException {
        throw new ModelProviderException("PROVIDER_NOT_CONFIGURED", false);
    }
}
