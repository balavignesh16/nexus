package com.nexus.rule.ai.provider;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.nexus.rule.ai.AiExplanationContext;
import com.nexus.rule.ai.AiExplanationPromptBuilder;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests OpenAiReasoningBoundary against a real, local, JDK-builtin
 * HttpServer (Tier 4 Phase 2B) - no live provider, no test dependency
 * beyond what is already on the classpath (logback-classic is already a
 * transitive dependency via spring-boot-starter-logging).
 */
class OpenAiReasoningBoundaryTest {

    private HttpServer server;
    private int port;
    private final AtomicInteger requestCount = new AtomicInteger(0);
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final AiExplanationPromptBuilder promptBuilder = new AiExplanationPromptBuilder(objectMapper);

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        port = server.getAddress().getPort();
        requestCount.set(0);
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private OpenAiReasoningBoundary boundary(int timeoutMs) {
        return boundary(timeoutMs, port);
    }

    private OpenAiReasoningBoundary boundary(int timeoutMs, int targetPort) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeoutMs);
        factory.setReadTimeout(timeoutMs);
        RestClient client = RestClient.builder()
                .baseUrl("http://localhost:" + targetPort)
                .defaultHeader("Authorization", "Bearer test-api-key-12345")
                .requestFactory(factory)
                .build();
        return new OpenAiReasoningBoundary(client, promptBuilder, "gpt-4o-mini", 1024);
    }

    private AiExplanationContext sampleContext() {
        return new AiExplanationContext(
                Map.of("name", "High Temperature Alert", "conditions", List.of()),
                Map.of("outcome", "matched"),
                Instant.now(), "All conditions met", List.of());
    }

    private String successEnvelope(String modelContent) throws IOException {
        return "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":"
                + objectMapper.writeValueAsString(modelContent) + "}}]}";
    }

    private void respondAlways(int status, String body, long delayMs) {
        server.createContext("/chat/completions", exchange -> {
            requestCount.incrementAndGet();
            if (delayMs > 0) {
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            writeResponse(exchange, status, body);
        });
    }

    private void respondCapturingRequest(int status, String body, AtomicReference<String> capturedRequestBody,
                                          AtomicReference<String> capturedAuthHeader) {
        server.createContext("/chat/completions", exchange -> {
            requestCount.incrementAndGet();
            capturedRequestBody.set(readAll(exchange));
            capturedAuthHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
            writeResponse(exchange, status, body);
        });
    }

    private void respondFirstThenSecond(int firstStatus, String firstBody, int secondStatus, String secondBody) {
        server.createContext("/chat/completions", exchange -> {
            int count = requestCount.incrementAndGet();
            if (count == 1) {
                writeResponse(exchange, firstStatus, firstBody);
            } else {
                writeResponse(exchange, secondStatus, secondBody);
            }
        });
    }

    private void writeResponse(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = (body == null ? "" : body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private String readAll(HttpExchange exchange) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        exchange.getRequestBody().transferTo(out);
        return out.toString(StandardCharsets.UTF_8);
    }

    // ---------- 1-6: success path, request shape, config ----------

    @Test
    void successfulResponse_returnsOptionalOfRawJson() throws IOException {
        respondAlways(200, successEnvelope("{\"matchId\":\"x\",\"summary\":\"s\",\"claims\":[]}"), 0);

        Optional<String> result = boundary(5000).generate(sampleContext());

        assertTrue(result.isPresent());
        assertTrue(result.get().contains("\"summary\":\"s\""));
    }

    @Test
    void request_containsSystemAndUserMessages() throws IOException {
        AtomicReference<String> capturedBody = new AtomicReference<>();
        AtomicReference<String> capturedAuth = new AtomicReference<>();
        respondCapturingRequest(200, successEnvelope("{\"matchId\":\"x\",\"summary\":\"s\",\"claims\":[]}"), capturedBody, capturedAuth);

        boundary(5000).generate(sampleContext());

        JsonNode request = objectMapper.readTree(capturedBody.get());
        assertEquals(2, request.get("messages").size());
        assertEquals("system", request.get("messages").get(0).get("role").asText());
        assertEquals("user", request.get("messages").get(1).get("role").asText());
        assertTrue(request.get("messages").get(1).get("content").asText().contains("High Temperature Alert"));
    }

    @Test
    void request_sanitizedContext_isExactlyWhatIsSent() throws IOException {
        AtomicReference<String> capturedBody = new AtomicReference<>();
        AtomicReference<String> capturedAuth = new AtomicReference<>();
        respondCapturingRequest(200, successEnvelope("{\"matchId\":\"x\",\"summary\":\"s\",\"claims\":[]}"), capturedBody, capturedAuth);

        boundary(5000).generate(sampleContext());

        JsonNode request = objectMapper.readTree(capturedBody.get());
        JsonNode userContent = objectMapper.readTree(request.get("messages").get(1).get("content").asText());
        assertEquals("High Temperature Alert", userContent.get("ruleSnapshot").get("name").asText());
        assertEquals("matched", userContent.get("evaluationEvidence").get("outcome").asText());
    }

    @Test
    void request_noForbiddenIdentifiers_areSerialized() throws IOException {
        AtomicReference<String> capturedBody = new AtomicReference<>();
        AtomicReference<String> capturedAuth = new AtomicReference<>();
        respondCapturingRequest(200, successEnvelope("{\"matchId\":\"x\",\"summary\":\"s\",\"claims\":[]}"), capturedBody, capturedAuth);

        boundary(5000).generate(sampleContext());

        JsonNode request = objectMapper.readTree(capturedBody.get());
        String userMessageContent = request.get("messages").get(1).get("content").asText();
        JsonNode userContent = objectMapper.readTree(userMessageContent);
        assertFalse(userContent.has("matchId"));
        assertFalse(userContent.has("ruleId"));
        assertFalse(userContent.has("eventId"));
        assertFalse(userContent.has("deviceId"));
        assertFalse(userContent.has("correlationId"));
        assertFalse(userContent.get("ruleSnapshot").has("ruleId"), "ruleSnapshot must already have ruleId stripped upstream by the mapper");
    }

    @Test
    void request_strictJsonSchemaResponseFormat_sentCorrectly() throws IOException {
        AtomicReference<String> capturedBody = new AtomicReference<>();
        AtomicReference<String> capturedAuth = new AtomicReference<>();
        respondCapturingRequest(200, successEnvelope("{\"matchId\":\"x\",\"summary\":\"s\",\"claims\":[]}"), capturedBody, capturedAuth);

        boundary(5000).generate(sampleContext());

        JsonNode request = objectMapper.readTree(capturedBody.get());
        JsonNode responseFormat = request.get("response_format");
        assertEquals("json_schema", responseFormat.get("type").asText());
        assertTrue(responseFormat.get("json_schema").get("strict").asBoolean());
        JsonNode schema = responseFormat.get("json_schema").get("schema");
        assertEquals(false, schema.get("additionalProperties").asBoolean());
        assertTrue(schema.get("properties").has("claims"));
        assertFalse(schema.get("properties").has("actionNote"), "actionNote must never be offered to the model");
        assertFalse(schema.get("properties").has("generatedBy"), "generatedBy must never be offered to the model");
    }

    @Test
    void request_modelConfiguration_isUsed() throws IOException {
        AtomicReference<String> capturedBody = new AtomicReference<>();
        AtomicReference<String> capturedAuth = new AtomicReference<>();
        respondCapturingRequest(200, successEnvelope("{\"matchId\":\"x\",\"summary\":\"s\",\"claims\":[]}"), capturedBody, capturedAuth);

        boundary(5000).generate(sampleContext());

        JsonNode request = objectMapper.readTree(capturedBody.get());
        assertEquals("gpt-4o-mini", request.get("model").asText());
        assertEquals(1024, request.get("max_tokens").asInt());
    }

    // ---------- 7-17: failure/timeout/retry semantics ----------

    @Test
    void timeout_returnsEmpty() throws IOException {
        respondAlways(200, successEnvelope("{}"), 3000);

        Optional<String> result = boundary(200).generate(sampleContext());

        assertTrue(result.isEmpty());
    }

    @Test
    void connectionFailure_returnsEmpty() {
        server.stop(0);

        Optional<String> result = boundary(1000, port).generate(sampleContext());

        assertTrue(result.isEmpty());
    }

    @Test
    void http401_returnsEmpty() {
        respondAlways(401, "{}", 0);

        Optional<String> result = boundary(5000).generate(sampleContext());

        assertTrue(result.isEmpty());
        assertEquals(1, requestCount.get(), "401 must not be retried");
    }

    @Test
    void http403_returnsEmpty() {
        respondAlways(403, "{}", 0);

        Optional<String> result = boundary(5000).generate(sampleContext());

        assertTrue(result.isEmpty());
        assertEquals(1, requestCount.get(), "403 must not be retried");
    }

    @Test
    void http429_returnsEmpty() {
        respondAlways(429, "{}", 0);

        Optional<String> result = boundary(5000).generate(sampleContext());

        assertTrue(result.isEmpty());
        assertEquals(1, requestCount.get(), "429 must not be retried");
    }

    @Test
    void http4xxOther_returnsEmptyWithoutRetry() {
        respondAlways(400, "{}", 0);

        Optional<String> result = boundary(5000).generate(sampleContext());

        assertTrue(result.isEmpty());
        assertEquals(1, requestCount.get());
    }

    @Test
    void http5xx_retriesExactlyOnce() {
        respondAlways(500, "{}", 0);

        boundary(5000).generate(sampleContext());

        assertEquals(2, requestCount.get(), "exactly one retry - two total attempts");
    }

    @Test
    void secondHttp5xx_returnsEmpty() {
        respondAlways(503, "{}", 0);

        Optional<String> result = boundary(5000).generate(sampleContext());

        assertTrue(result.isEmpty());
        assertEquals(2, requestCount.get());
    }

    @Test
    void successfulRetryAfterFirst5xx_returnsRawJson() throws IOException {
        respondFirstThenSecond(500, "{}", 200, successEnvelope("{\"matchId\":\"x\",\"summary\":\"recovered\",\"claims\":[]}"));

        Optional<String> result = boundary(5000).generate(sampleContext());

        assertTrue(result.isPresent());
        assertTrue(result.get().contains("recovered"));
        assertEquals(2, requestCount.get());
    }

    @Test
    void emptyResponseBody_handledSafely_noException() {
        respondAlways(200, "", 0);

        Optional<String> result = boundary(5000).generate(sampleContext());

        assertTrue(result.isEmpty());
    }

    @Test
    void oversizedResponse_handledSafely() throws IOException {
        String hugeContent = "x".repeat(60_000);
        respondAlways(200, successEnvelope(hugeContent), 0);

        Optional<String> result = boundary(5000).generate(sampleContext());

        assertTrue(result.isEmpty(), "content exceeding the safety size bound must be discarded, not passed downstream");
    }

    // ---------- 18: API key sent correctly, never logged ----------

    @Test
    void apiKey_sentInAuthorizationHeader_butNeverLogged() throws IOException {
        AtomicReference<String> capturedBody = new AtomicReference<>();
        AtomicReference<String> capturedAuth = new AtomicReference<>();
        respondCapturingRequest(200, successEnvelope("{\"matchId\":\"x\",\"summary\":\"s\",\"claims\":[]}"), capturedBody, capturedAuth);

        Logger logger = (Logger) LoggerFactory.getLogger(OpenAiReasoningBoundary.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            boundary(5000).generate(sampleContext());
        } finally {
            logger.detachAppender(appender);
        }

        assertEquals("Bearer test-api-key-12345", capturedAuth.get(), "the real key must actually be sent");
        for (ILoggingEvent event : appender.list) {
            assertFalse(event.getFormattedMessage().contains("test-api-key-12345"),
                    "the API key must never appear in a log message: " + event.getFormattedMessage());
        }
    }
}
