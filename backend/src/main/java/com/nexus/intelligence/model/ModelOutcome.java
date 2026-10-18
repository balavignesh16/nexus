package com.nexus.intelligence.model;

import java.util.Map;

/**
 * The only results of asking a model. {@link Completed} is still UNTRUSTED content, parsed into the requested schema; the caller must ground or validate it
 * before use. {@link Refused} carries a fixed code. Both state the identity that produced (or would have produced) the output.
 */
public sealed interface ModelOutcome permits ModelOutcome.Completed, ModelOutcome.Refused {
    ProviderIdentity identity();

    record Completed(Map<String, Object> values, ProviderIdentity identity) implements ModelOutcome {
        public Completed {
            values = Map.copyOf(values);
        }
    }

    record Refused(RefusalCode code, StructuredOutput.Code detail, ProviderIdentity identity) implements ModelOutcome {}

    default boolean completed() {
        return this instanceof Completed;
    }
}
