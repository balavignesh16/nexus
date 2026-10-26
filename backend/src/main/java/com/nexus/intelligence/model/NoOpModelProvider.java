package com.nexus.intelligence.model;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

/** The default: no model (neither the hosted-compatible provider nor the local one is enabled). Every AI feature then uses its deterministic implementation and nothing leaves the process. */
@Component
@ConditionalOnExpression("!'${nexus.ai.enabled:false}'.equals('true') && !'${nexus.ai.ollama.enabled:false}'.equals('true')")
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
