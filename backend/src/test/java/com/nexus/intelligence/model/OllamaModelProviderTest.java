package com.nexus.intelligence.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P6-161, P6-162, P6-163: the local Ollama provider against a fake loopback server speaking the Ollama protocol. It proves the request it sends, how each failure
 * becomes a fixed code, that only loopback addresses and locally hosted models are accepted, that the gateway still owns consent and structure, and that an
 * unreachable server leaves the deterministic path in charge. No real model is involved.
 */
class OllamaModelProviderTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer server;
    private final AtomicInteger chatCalls = new AtomicInteger();
    private final AtomicReference<String> chatBody = new AtomicReference<>();
    private final AtomicReference<Integer> chatStatus = new AtomicReference<>(200);
    private final AtomicReference<String> chatReply = new AtomicReference<>("{\"message\":{\"role\":\"assistant\",\"content\":\"{\\\"ok\\\":true}\"},\"done\":true}");
    private final AtomicReference<String> showReply = new AtomicReference<>("{\"capabilities\":[\"completion\",\"tools\"],\"details\":{\"parameter_size\":\"3.2B\",\"quantization_level\":\"Q4_K_M\",\"family\":\"llama\"}}");
    private final AtomicReference<String> tagsReply = new AtomicReference<>("{\"models\":[{\"name\":\"llama3.2:3b\",\"model\":\"llama3.2:3b\",\"digest\":\"abc123\",\"size\":2019393189}]}");
    private volatile long chatDelayMillis = 0;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/api/chat", exchange -> {
            chatCalls.incrementAndGet();
            chatBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            try {
                Thread.sleep(chatDelayMillis);
            } catch (InterruptedException ignored) { /* test server */ }
            respond(exchange, chatStatus.get(), chatReply.get());
        });
        server.createContext("/api/show", exchange -> {
            exchange.getRequestBody().readAllBytes();
            respond(exchange, 200, showReply.get());
        });
        server.createContext("/api/tags", exchange -> respond(exchange, 200, tagsReply.get()));
        server.createContext("/api/version", exchange -> respond(exchange, 200, "{\"version\":\"0.21.2\"}"));
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private OllamaModelProvider provider() {
        return new OllamaModelProvider(url(), "llama3.2:3b", 256, 5_000);
    }

    // ------------------------------------------------------------------ the request and the replies

    @Test
    void p6_161_theRequestAsksForDeterministicJsonFromTheNamedLocalModelAndTheReplyIsReturnedAsUntrustedText() throws Exception {
        String reply = provider().complete("the assembled prompt", 20_000);
        assertThat(reply).isEqualTo("{\"ok\":true}");
        JsonNode sent = MAPPER.readTree(chatBody.get());
        assertThat(sent.get("model").asText()).isEqualTo("llama3.2:3b");
        assertThat(sent.get("stream").asBoolean()).isFalse();
        assertThat(sent.get("format").asText()).isEqualTo("json");
        assertThat(sent.get("options").get("temperature").asInt()).isZero();
        assertThat(sent.get("options").get("seed").asInt()).isZero();
        assertThat(sent.get("options").get("num_predict").asInt()).isEqualTo(256);
        assertThat(sent.get("messages").get(0).get("role").asText()).isEqualTo("user");
        assertThat(sent.get("messages").get(0).get("content").asText()).isEqualTo("the assembled prompt");
        assertThat(provider().available()).isTrue();
        assertThat(provider().identity().providerId()).isEqualTo("ollama-local");
        assertThat(provider().identity().modelId()).isEqualTo("llama3.2:3b");
        assertThat(provider().identity().configHash()).hasSize(16).isEqualTo(provider().identity().configHash());
        assertThat(new OllamaModelProvider(url(), "phi3:mini", 256, 5_000).identity().configHash()).isNotEqualTo(provider().identity().configHash());
    }

    @Test
    void p6_161_everyFailureBecomesAFixedCodeWithoutProviderTextAndOnlyTransientOnesMayBeRetried() {
        chatStatus.set(404);
        chatReply.set("{\"error\":\"model 'llama3.2:3b' not found, try pulling it first\"}");
        assertThatThrownBy(() -> provider().complete("p", 1000)).isInstanceOfSatisfying(ModelProviderException.class, e -> {
            assertThat(e.getMessage()).isEqualTo("MODEL_NOT_FOUND");
            assertThat(e.transientFailure()).isFalse();
        });
        chatStatus.set(500);
        assertThatThrownBy(() -> provider().complete("p", 1000)).isInstanceOfSatisfying(ModelProviderException.class, e -> {
            assertThat(e.getMessage()).isEqualTo("HTTP_5XX");
            assertThat(e.transientFailure()).isTrue();
        });
        chatStatus.set(400);
        assertThatThrownBy(() -> provider().complete("p", 1000)).hasMessage("HTTP_400");
        chatStatus.set(200);
        chatReply.set("not json at all <html>");
        assertThatThrownBy(() -> provider().complete("p", 1000)).hasMessage("BAD_RESPONSE");
        chatReply.set("{\"message\":{\"role\":\"assistant\",\"content\":\"\"},\"done\":true}");
        assertThatThrownBy(() -> provider().complete("p", 1000)).hasMessage("NO_CONTENT");
        chatReply.set("{\"message\":{\"content\":\"" + "x".repeat(OllamaModelProvider.MAX_RESPONSE_BYTES) + "\"}}");
        assertThatThrownBy(() -> provider().complete("p", 1000)).hasMessage("RESPONSE_TOO_LARGE");
    }

    @Test
    void p6_162_anUnreachableOrSlowServerIsATransientFailureAndNeverAnException() {
        chatDelayMillis = 2_500;
        OllamaModelProvider slow = new OllamaModelProvider(url(), "llama3.2:3b", 256, 1_000);
        assertThatThrownBy(() -> slow.complete("p", 1000)).isInstanceOfSatisfying(ModelProviderException.class, e -> {
            assertThat(e.getMessage()).isEqualTo("TIMEOUT");
            assertThat(e.transientFailure()).isTrue();
        });
        OllamaModelProvider provider = provider();
        server.stop(0);
        assertThatThrownBy(() -> provider.complete("p", 1000)).isInstanceOfSatisfying(ModelProviderException.class, e -> {
            assertThat(e.getMessage()).isEqualTo("NETWORK");
            assertThat(e.transientFailure()).isTrue();
        });
    }

    // ------------------------------------------------------------------ nothing leaves the machine

    @Test
    void p6_163_onlyLoopbackAddressesAreAccepted() {
        for (String ok : List.of("http://localhost:11434", "http://127.0.0.1:11434", "http://127.5.5.5:1", "http://[::1]:11434", "http://LOCALHOST:11434/", "https://localhost:11434"))
            assertThat(OllamaModelProvider.loopbackOnly(ok)).as(ok).isNotNull();
        for (String bad : List.of("http://example.com:11434", "http://192.168.1.5:11434", "http://10.0.0.1", "http://0.0.0.0:11434", "http://localhost.evil.com", "http://127.0.0.1.evil.com",
                "http://localhost@evil.com", "http://user:secret@localhost:11434", "ftp://localhost", "http://[::1]:11434/path", "http://localhost:11434/api", "localhost:11434", "", "http://", "http://128.0.0.1",
                "http://[2001:db8::1]:11434", "http://169.254.169.254")) {
            assertThatThrownBy(() -> OllamaModelProvider.loopbackOnly(bad)).as(bad).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> new OllamaModelProvider(bad, "llama3.2:3b", 256, 5_000)).as(bad).isInstanceOf(IllegalStateException.class);
        }
        assertThatThrownBy(() -> new OllamaModelProvider("http://user:secret@evil.example", "llama3.2:3b", 256, 5_000)).hasMessageNotContaining("secret").hasMessageNotContaining("evil.example");
    }

    @Test
    void p6_163_aModelThatOllamaForwardsToAHostedServiceIsRefusedByNameAndByTheServersOwnDescription() throws Exception {
        for (String cloud : List.of("qwen3.5:cloud", "gpt-oss:120b-cloud", "kimi-k2:1t-cloud", "QWEN3.5:CLOUD"))
            assertThatThrownBy(() -> new OllamaModelProvider(url(), cloud, 256, 5_000)).as(cloud).hasMessageContaining("remotely hosted");
        assertThat(OllamaModelProvider.namesRemoteModel("llama3.2:3b")).isFalse();
        assertThatThrownBy(() -> new OllamaModelProvider(url(), "bad name; rm", 256, 5_000)).isInstanceOf(IllegalStateException.class);
        // a name that looks local but that the server describes as remote is refused before any evidence is sent
        showReply.set("{\"remote_host\":\"https://ollama.com:443\",\"remote_model\":\"big-model\",\"capabilities\":[\"completion\"]}");
        assertThatThrownBy(() -> provider().complete("device evidence", 1000)).hasMessage("REMOTE_MODEL_REFUSED");
        assertThat(chatCalls.get()).as("the chat endpoint is never called for a remote model").isZero();
    }

    @Test
    void p6_163_theGatewayStillOwnsConsentAndStructureAndTheOfflinePathWinsWhenTheServerIsDown() {
        GuardSettings settings = new GuardSettings(2, Duration.ofSeconds(5), 0, 5, Duration.ofSeconds(30), 20_000, 20_000);
        ModelGateway noConsent = new ModelGateway(provider(), new AiCallGuard(settings, Clock.systemUTC()), settings, false);
        ModelRequest request = new ModelRequest(ModelPurpose.ASSISTANT_SELECTION, "pick", List.of(DataBlock.evidence("evidence", "ref | label | 21.5")),
                StructuredOutput.Schema.of(Map.of("ok", StructuredOutput.FieldType.BOOLEAN), Set.of("ok")));
        ModelOutcome refused = noConsent.ask(request);
        assertThat(refused).isInstanceOfSatisfying(ModelOutcome.Refused.class, r -> assertThat(r.code()).isEqualTo(RefusalCode.NO_CONSENT));
        assertThat(chatCalls.get()).as("device evidence is not sent without the separate consent").isZero();

        ModelGateway consented = new ModelGateway(provider(), new AiCallGuard(settings, Clock.systemUTC()), settings, true);
        assertThat(consented.ask(request)).isInstanceOfSatisfying(ModelOutcome.Completed.class, c -> assertThat(c.values()).containsEntry("ok", true));
        assertThat(chatCalls.get()).isEqualTo(1);
        JsonNode sent;
        try {
            sent = MAPPER.readTree(chatBody.get());
        } catch (IOException e) {
            throw new AssertionError(e);
        }
        assertThat(sent.get("messages").get(0).get("content").asText()).contains("ref | label | 21.5");

        chatReply.set("{\"message\":{\"role\":\"assistant\",\"content\":\"I think it is fine {not json}\"},\"done\":true}");
        assertThat(consented.ask(request)).isInstanceOfSatisfying(ModelOutcome.Refused.class, r -> assertThat(r.code()).isEqualTo(RefusalCode.OUTPUT_INVALID));

        server.stop(0);
        ModelOutcome down = consented.ask(request);
        assertThat(down).isInstanceOf(ModelOutcome.Refused.class);
    }

    // ------------------------------------------------------------------ what the server really has

    @Test
    void p6_161_theProbeReportsWhatTheServerActuallyHasInsteadOfAssumingFromTheName() {
        OllamaProbe.Inspection ok = OllamaProbe.inspect(url(), "llama3.2:3b", Duration.ofSeconds(3));
        assertThat(ok.reachable()).isTrue();
        assertThat(ok.version()).isEqualTo("0.21.2");
        assertThat(ok.installed()).isTrue();
        assertThat(ok.digest()).isEqualTo("abc123");
        assertThat(ok.sizeBytes()).isEqualTo(2019393189L);
        assertThat(ok.parameterSize()).isEqualTo("3.2B");
        assertThat(ok.quantization()).isEqualTo("Q4_K_M");
        assertThat(ok.capabilities()).containsExactly("completion", "tools");
        assertThat(ok.supportsTools()).isTrue();
        assertThat(ok.remotelyHosted()).isFalse();

        showReply.set("{\"capabilities\":[\"completion\"],\"details\":{\"family\":\"phi3\"}}");
        assertThat(OllamaProbe.inspect(url(), "llama3.2:3b", Duration.ofSeconds(3)).supportsTools()).as("absence of the tools capability is reported, not assumed").isFalse();
        assertThat(OllamaProbe.inspect(url(), "missing:1b", Duration.ofSeconds(3)).installed()).isFalse();
        showReply.set("{\"remote_host\":\"https://ollama.com\",\"capabilities\":[\"completion\",\"tools\"]}");
        assertThat(OllamaProbe.inspect(url(), "llama3.2:3b", Duration.ofSeconds(3)).remotelyHosted()).isTrue();
        assertThatThrownBy(() -> OllamaProbe.inspect("http://example.com:11434", "llama3.2:3b", Duration.ofSeconds(1))).isInstanceOf(IllegalStateException.class);
        server.stop(0);
        OllamaProbe.Inspection down = OllamaProbe.inspect(url(), "llama3.2:3b", Duration.ofSeconds(1));
        assertThat(down.reachable()).isFalse();
        assertThat(down.problem()).contains("did not answer");
    }
}
