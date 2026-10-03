package com.nexus.device.template.api;
import jakarta.validation.constraints.*;
import java.util.*;
public record TemplateCapabilityRequest(@NotNull UUID capabilityDefinitionId,boolean required,String defaultUnit,
 @PositiveOrZero int displayOrder,@Positive Integer freshnessThresholdSeconds,Map<String,Object> configuration) {}
