package com.nexus.intelligence.model;

/**
 * The provider-independent boundary to a model: text in, text out, nothing else. An implementation receives the assembled prompt and returns raw TEXT that is
 * untrusted by definition; it has no access to any backend service, repository, command path or configuration. It is called only through
 * {@link ModelGateway}, which owns consent, bounds, timeouts and failure containment.
 */
public interface ModelProvider {
    ProviderIdentity identity();

    /** False for the offline, model-free provider: the gateway then never calls {@link #complete}. */
    boolean available();

    String complete(String prompt, int maxOutputChars) throws ModelProviderException;
}
