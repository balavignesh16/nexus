package com.nexus.rule.dryrun;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * POST /api/v1/rules/dry-run - hypothetical evaluation (C19). Strictly read-only: it evaluates and reports, it never persists, dispatches or
 * publishes. Like every other NEXUS endpoint it has no application-level authentication; the response is deliberately limited to condition
 * evidence, eligibility and arbitration for ONE scope device (the same evidence the rule-match history and the twin APIs already expose).
 * <p>
 * The input limit is enforced HERE, by the handler that owns the endpoint: the body is read through a bounded stream (never more than the limit
 * plus one byte is held) BEFORE any JSON binding and before any rule is looked at, whatever the URI spelling, Content-Length or transfer
 * encoding that got the request this far. {@link DryRunBodyLimitFilter} only adds an earlier refusal for a declared oversized length.
 */
@RestController
@RequestMapping("/api/v1/rules")
public class RuleDryRunController {
    private final DryRunService service;
    /** The application mapper tolerates unknown properties; a dry-run request must not: an ignored field is a silent misconfiguration. */
    private final ObjectMapper strict;
    public RuleDryRunController(DryRunService service, ObjectMapper mapper) {
        this.service = service;
        this.strict = mapper.copy().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);
    }

    @PostMapping(value = "/dry-run", consumes = MediaType.APPLICATION_JSON_VALUE)
    public DryRunResponse dryRun(HttpServletRequest http) throws IOException {
        byte[] body = readBounded(http.getInputStream(), DryRunService.MAX_INPUT_BYTES);
        JsonNode tree;
        try { tree = strict.readTree(body); }
        catch (JsonProcessingException malformed) {
            throw new DryRunRejection("INVALID_REQUEST", "The request body is not valid JSON: " + firstLine(malformed.getOriginalMessage()), HttpStatus.BAD_REQUEST);
        }
        if (tree == null || tree.isMissingNode() || !tree.isObject())
            throw new DryRunRejection("INVALID_REQUEST", "The request body must be a JSON object", HttpStatus.BAD_REQUEST);
        DryRunRequest request;
        try { request = strict.treeToValue(tree, DryRunRequest.class); }
        catch (JsonProcessingException | IllegalArgumentException malformed) {
            throw new DryRunRejection("INVALID_REQUEST", "The request could not be bound: " + firstLine(malformed.getMessage()), HttpStatus.BAD_REQUEST);
        }
        return service.run(request);
    }

    /** Reads at most {@code limit} bytes; one byte more is refused immediately, so an oversized or endless body is never held in memory. */
    static byte[] readBounded(InputStream in, int limit) throws IOException {
        var out = new ByteArrayOutputStream(Math.min(limit, 16 * 1024));
        byte[] buffer = new byte[8192];
        int total = 0, read;
        while ((read = in.read(buffer)) != -1) {
            total += read;
            if (total > limit) throw new DryRunRejection("INPUT_TOO_LARGE", "Dry-run input is limited to " + limit + " bytes; nothing was evaluated.", HttpStatus.PAYLOAD_TOO_LARGE);
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private static String firstLine(String message) {
        if (message == null) return "invalid input";
        int end = message.indexOf((char) 10);
        return end < 0 ? message : message.substring(0, end);
    }

    @ExceptionHandler(DryRunRejection.class)
    public ResponseEntity<Map<String, Object>> rejected(DryRunRejection rejection) {
        var body = new LinkedHashMap<String, Object>();
        body.put("status", rejection.status().value());
        body.put("error", rejection.status().getReasonPhrase());
        body.put("code", rejection.code());
        body.put("message", rejection.getMessage());
        body.put("timestamp", Instant.now());
        if (!rejection.details().isEmpty()) body.put("details", rejection.details());
        return ResponseEntity.status(rejection.status()).body(body);
    }
}
