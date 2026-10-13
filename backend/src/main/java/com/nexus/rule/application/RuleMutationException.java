package com.nexus.rule.application;

import org.springframework.http.HttpStatusCode;
import org.springframework.web.server.ResponseStatusException;

public class RuleMutationException extends ResponseStatusException {
    public RuleMutationException(int status,String message) { super(HttpStatusCode.valueOf(status),message); }
}
