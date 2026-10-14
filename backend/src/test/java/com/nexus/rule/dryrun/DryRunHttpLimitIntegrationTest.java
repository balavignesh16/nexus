package com.nexus.rule.dryrun;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.rule.application.RuleEvaluator;
import com.nexus.rule.domain.*;
import com.nexus.rule.trigger.TypedTriggerSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * B1: the 1 MiB dry-run input limit over the REAL embedded HTTP server (not MockMvc): every URI representation that reaches the dry-run handler
 * is bounded, with and without a Content-Length (chunked), exactly at and just above the limit, before any JSON binding and before any rule is
 * evaluated. The handler itself enforces the bound, so no URL spelling can route around it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DryRunHttpLimitIntegrationTest extends TypedTriggerSupport {
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @SpyBean RuleEvaluator evaluator;
    @SpyBean DryRunEngine engine;
    private static final String CANONICAL = "/api/v1/rules/dry-run";
    private static final int LIMIT = 1024 * 1024;
    private final HttpClient http = HttpClient.newHttpClient();
    private static final List<TriggerSubscription> TELEMETRY = List.of(new TriggerSubscription(TriggerType.ACCEPTED_TELEMETRY));

    private record Reply(int status, JsonNode body) {}

    private Reply send(String rawPath, byte[] body, boolean chunked, String contentType) throws Exception {
        var publisher = chunked ? HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(body)) : HttpRequest.BodyPublishers.ofByteArray(body);
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + rawPath)).header("Content-Type", contentType).POST(publisher).build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofString());
        String text = response.body();
        JsonNode parsed;
        try { parsed = text == null || text.isBlank() ? json.createObjectNode() : json.readTree(text); } catch (Exception notJson) { parsed = json.createObjectNode(); }
        return new Reply(response.statusCode(), parsed);
    }
    private Reply send(String rawPath, byte[] body, boolean chunked) throws Exception { return send(rawPath, body, chunked, "application/json"); }

    /** A valid request padded with a label so that the whole body is exactly {@code size} bytes. */
    private byte[] requestOfSize(UUID device, UUID rule, int size) throws Exception {
        String head = json.writeValueAsString(new LinkedHashMap<>(Map.of("mode", "CURRENT_STATE", "scope", Map.of("deviceId", device.toString()),
            "versions", List.of(Map.of("ruleId", rule.toString(), "label", "")), "trigger", Map.of("type", "ACCEPTED_TELEMETRY", "deviceId", device.toString()))));
        int pad = size - head.getBytes(StandardCharsets.UTF_8).length;
        assertTrue(pad >= 0);
        String full = head.replace("\"label\":\"\"", "\"label\":\"" + "x".repeat(pad) + "\"");
        assertEquals(size, full.getBytes(StandardCharsets.UTF_8).length, "the padded body has exactly the requested size");
        return full.getBytes(StandardCharsets.UTF_8);
    }
    private UUID rule(UUID device, UUID target) {
        return typedRule(device, "http limit", 1, TELEMETRY, List.of(temperatureAbove("10")), List.of(ledCommand(target, true)));
    }

    /** Every spelling that Spring MVC (or Tomcat) may map to the dry-run handler. Which of them actually reach it is asserted from the response. */
    private static final List<String> VARIANTS = List.of(CANONICAL, CANONICAL + ";x=1", CANONICAL + ";jsessionid=abc", CANONICAL + "%3Bx=1", "/api/v1/rules/;y=2/dry-run", "/api/v1/;y=2/rules/dry-run",
        "/api/v1/rules/./dry-run", "//api/v1/rules/dry-run", "/api/v1//rules/dry-run", CANONICAL + "/", CANONICAL + "?q=1", "/api/v1/rules/%64ry-run", "/api/v1/rules/dry%2Drun");

    @Test void everyUriSpellingThatReachesTheHandlerEnforcesTheLimitAndNothingOversizedIsEvaluated() throws Exception {
        UUID device = device(), target = device();
        UUID rule = rule(device, target);
        byte[] oversize = requestOfSize(device, rule, LIMIT + 1);
        var reachable = new ArrayList<String>();
        for (String variant : VARIANTS) {
            for (boolean chunked : new boolean[]{false, true}) {
                Reply reply = null;
                try { reply = send(variant, oversize, chunked); } catch (java.io.IOException refusedAndClosed) { /* refused, connection closed while streaming */ }
                if (reply == null) { reachable.add(variant + (chunked ? " [chunked]" : "")); continue; }
                // Whatever the spelling and transfer encoding: either refused as oversized, or the server does not route it here at all.
                // Never a processed decision.
                assertFalse(reply.body().has("versions"), variant + (chunked ? " [chunked]" : "") + " produced a decision for an oversized body: " + reply.body());
                assertTrue(reply.status() == 413 || reply.status() == 404 || reply.status() == 405, variant + " -> " + reply.status() + " " + reply.body());
                if (reply.status() == 413) { assertEquals("INPUT_TOO_LARGE", reply.body().path("code").asText(), variant); reachable.add(variant + (chunked ? " [chunked]" : "")); }
            }
        }
        for (String mustBeRouted : List.of(CANONICAL, CANONICAL + ";x=1", CANONICAL + ";jsessionid=abc", CANONICAL + "?q=1")) {
            assertTrue(reachable.contains(mustBeRouted), mustBeRouted + " is routed to the handler without Content-Length too: " + reachable);
            assertTrue(reachable.contains(mustBeRouted + " [chunked]"), mustBeRouted + " [chunked] is refused: " + reachable);
        }
        assertTrue(reachable.contains(CANONICAL), "the canonical spelling reaches the handler: " + reachable);
        System.out.println("DRYRUN_HTTP_REACHABLE_VARIANTS " + reachable);
        verifyNoInteractions(evaluator);
        verify(engine, never()).evaluate(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test void exactlyTheLimitIsProcessedAndOneByteMoreIsNot_withAndWithoutContentLength() throws Exception {
        UUID device = device(), target = device();
        UUID rule = rule(device, target);
        for (boolean chunked : new boolean[]{false, true}) {
            var ok = send(CANONICAL, requestOfSize(device, rule, LIMIT), chunked);
            assertEquals(200, ok.status(), "exactly 1 MiB is within the limit (chunked=" + chunked + "): " + ok.body());
            var over = send(CANONICAL, requestOfSize(device, rule, LIMIT + 1), chunked);
            assertEquals(413, over.status(), "chunked=" + chunked);
            assertEquals("INPUT_TOO_LARGE", over.body().path("code").asText());
            assertEquals(413, over.body().path("status").asInt());
            assertFalse(over.body().has("versions"), "no decision accompanies the refusal");
        }
    }

    @Test void aHugeBodyIsCutOffWithoutBeingBufferedOrBound() throws Exception {
        // 32 MiB of 'a' streamed with no declared length: the server must refuse after at most the limit plus one chunk, not accept it all.
        byte[] huge = new byte[32 * 1024 * 1024];
        Arrays.fill(huge, (byte) 'a');
        try {
            var reply = send(CANONICAL, huge, true);
            assertEquals(413, reply.status());
        } catch (java.io.IOException refusedAndClosed) {
            // The server answered 413 and closed the connection while the client was still streaming: the body was refused, not consumed.
        }
        verifyNoInteractions(evaluator);
    }

    @Test void malformedAndWronglyTypedInputGetTypedErrorsAndUnrelatedEndpointsAreNotLimited() throws Exception {
        var malformed = send(CANONICAL, "{\"mode\":".getBytes(StandardCharsets.UTF_8), false);
        assertEquals(400, malformed.status());
        assertEquals("INVALID_REQUEST", malformed.body().path("code").asText());
        var notAnObject = send(CANONICAL, "[1,2]".getBytes(StandardCharsets.UTF_8), true);
        assertEquals(400, notAnObject.status());
        assertEquals("INVALID_REQUEST", notAnObject.body().path("code").asText());
        var empty = send(CANONICAL, new byte[0], false);
        assertEquals(400, empty.status());
        var wrongType = send(CANONICAL, "{}".getBytes(StandardCharsets.UTF_8), false, "text/plain");
        assertEquals(415, wrongType.status());
        verifyNoInteractions(evaluator);

        // Other endpoints keep their own behavior: a 2 MiB body to a different rule endpoint is not refused as dry-run input.
        byte[] big = ("{\"definition\":{\"name\":\"" + "x".repeat(2 * LIMIT) + "\"},\"expectedRevision\":0}").getBytes(StandardCharsets.UTF_8);
        var other = send("/api/v1/rules/drafts", big, false);
        assertNotEquals("INPUT_TOO_LARGE", other.body().path("code").asText(), "the dry-run limit must not leak onto unrelated endpoints");
        var near = send("/api/v1/rules/dry-run-other", big, false);
        assertTrue(near.status() == 404 || near.status() == 405, "an unrelated near-miss path is not the dry-run endpoint: " + near.status());
        assertNotEquals("INPUT_TOO_LARGE", near.body().path("code").asText());
    }
}
