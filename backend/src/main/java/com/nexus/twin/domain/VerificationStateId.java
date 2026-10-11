package com.nexus.twin.domain;

import java.io.Serializable;
import java.util.UUID;

public record VerificationStateId(UUID deviceId, String targetKey, String subtargetKey) implements Serializable {}
