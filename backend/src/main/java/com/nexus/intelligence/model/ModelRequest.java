package com.nexus.intelligence.model;

import java.util.List;
import java.util.Objects;

/**
 * A request to a model: a platform-owned instruction, untrusted data blocks and the output contract. {@code schema} is required: model output is only
 * ever accepted as a strict structured object.
 */
public record ModelRequest(ModelPurpose purpose, String instruction, List<DataBlock> data, StructuredOutput.Schema schema) {
    public ModelRequest {
        Objects.requireNonNull(purpose, "purpose");
        Objects.requireNonNull(instruction, "instruction");
        Objects.requireNonNull(schema, "schema");
        data = List.copyOf(data);
    }

    boolean carriesDeviceEvidence() {
        return data.stream().anyMatch(DataBlock::deviceEvidence);
    }
}
