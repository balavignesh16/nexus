package com.nexus.action.lifecycle;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/**
 * Read-only command lifecycle API (contract R37, ledger P4-097, P4-101, P4-103). GET only: no endpoint here changes a command, an outbox
 * row, a retry, a dead letter or a device, and none accepts a body or a parameter beyond the two paging values. Existing endpoints and
 * their response shapes are unchanged (additive routes under the existing prefix).
 */
@RestController
@RequestMapping("/api/v1/actions")
public class CommandLifecycleController {
    private final CommandLifecycleReader reader;

    public CommandLifecycleController(CommandLifecycleReader reader) {
        this.reader = reader;
    }

    @GetMapping("/{commandId}/lifecycle")
    public ResponseEntity<CommandLifecycleView> lifecycle(@PathVariable UUID commandId) {
        return reader.find(commandId).map(ResponseEntity::ok)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Command not found"));
    }

    /** Dead-lettered delivery obligations, newest first, keyset-paginated. An ACK timeout is a different fact and never appears here. */
    @GetMapping("/dead-letters")
    public ResponseEntity<CommandLifecycleReader.DeadLetterPage> deadLetters(
            @RequestParam(name = "limit", defaultValue = "" + CommandLifecycleReader.DEFAULT_PAGE) int limit,
            @RequestParam(name = "cursor", required = false) String cursor) {
        try {
            return ResponseEntity.ok(reader.deadLetters(limit, cursor));
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalid.getMessage());
        }
    }
}
