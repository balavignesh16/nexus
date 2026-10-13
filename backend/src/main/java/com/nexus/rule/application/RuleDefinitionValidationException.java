package com.nexus.rule.application;
import java.util.*;
import com.nexus.shared.exception.ValidationException;
public class RuleDefinitionValidationException extends ValidationException {
    private final List<Map<String,Object>> diagnostics;
    public RuleDefinitionValidationException(List<Map<String,Object>> diagnostics){super("Rule validation failed");this.diagnostics=List.copyOf(diagnostics);}
    public List<Map<String,Object>> diagnostics(){return diagnostics;}
}
