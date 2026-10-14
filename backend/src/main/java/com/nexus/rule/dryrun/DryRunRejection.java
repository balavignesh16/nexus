package com.nexus.rule.dryrun;

import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;

/** A request rejected BEFORE any evaluation: a stable machine code, an explanatory message and the HTTP status to use. */
public class DryRunRejection extends RuntimeException {
    private final String code;
    private final HttpStatus status;
    private final List<Map<String, Object>> details;
    public DryRunRejection(String code, String message, HttpStatus status) { this(code, message, status, List.of()); }
    public DryRunRejection(String code, String message, HttpStatus status, List<Map<String, Object>> details) {
        super(message, null, false, false);
        this.code = code; this.status = status; this.details = details == null ? List.of() : List.copyOf(details);
    }
    public String code() { return code; }
    public HttpStatus status() { return status; }
    public List<Map<String, Object>> details() { return details; }
}
