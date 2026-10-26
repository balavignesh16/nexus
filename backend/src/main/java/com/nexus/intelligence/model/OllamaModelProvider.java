package com.nexus.intelligence.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The LOCAL model provider ({@code nexus.ai.ollama.enabled=true}): a chat completion against an Ollama server on this machine. It returns text only, which the
 * gateway treats as untrusted like any provider text.
 *
 * <p>Egress boundary. The base URL must be a loopback address (localhost, 127.0.0.0/8, ::1) without user information; anything else stops startup, so device
 * evidence cannot be sent to another machine by a configuration slip. A model that Ollama hosts REMOTELY (its "cloud" models, which Ollama forwards to a hosted
 * service) is refused too: the name is checked at startup and the server's own description of the model is checked once before the first call. There is no
 * override in this milestone; sending evidence off the machine needs a separate, explicit decision.
 *
 * <p>Failures are fixed codes (never response text, a URL or a key). The reply is read with a hard size cap. The provider holds no state beyond the one-time
 * remote-model check and has no access to any backend service.
 */
@Component
@ConditionalOnProperty(name = "nexus.ai.ollama.enabled", havingValue = "true")
public class OllamaModelProvider implements ModelProvider {
    static final int MAX_RESPONSE_BYTES = 1_000_000;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient client;
    private final URI chatUri;
    private final URI showUri;
    private final String model;
    private final int maxOutputTokens;
    private final Duration requestTimeout;
    private final ProviderIdentity identity;
    private final AtomicReference<Boolean> remoteModel = new AtomicReference<>();

    public OllamaModelProvider(@Value("${nexus.ai.ollama.base-url:http://localhost:11434}") String baseUrl,
                               @Value("${nexus.ai.ollama.model:llama3.2:3b}") String model,
                               @Value("${nexus.ai.ollama.max-output-tokens:512}") int maxOutputTokens,
                               @Value("${nexus.ai.ollama.request-timeout-ms:60000}") long requestTimeoutMs) {
        URI base = loopbackOnly(baseUrl);
        if (model == null || !model.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,99}")) throw new IllegalStateException("nexus.ai.ollama.model is not a model name");
        if (namesRemoteModel(model)) throw new IllegalStateException("nexus.ai.ollama.model names a remotely hosted (cloud) model: device evidence would leave this machine");
        if (maxOutputTokens < 16 || maxOutputTokens > 8_192) throw new IllegalStateException("nexus.ai.ollama.max-output-tokens must be 16 to 8192");
        if (requestTimeoutMs < 1_000 || requestTimeoutMs > 600_000) throw new IllegalStateException("nexus.ai.ollama.request-timeout-ms must be 1000 to 600000");
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();
        this.chatUri = base.resolve("/api/chat");
        this.showUri = base.resolve("/api/show");
        this.model = model;
        this.maxOutputTokens = maxOutputTokens;
        this.requestTimeout = Duration.ofMillis(requestTimeoutMs);
        this.identity = new ProviderIdentity("ollama-local", model, ProviderIdentity.hashOf(model, base.getHost() + ":" + base.getPort(), String.valueOf(maxOutputTokens), "format=json", "temperature=0"));
    }

    /** Accepts only an http(s) URL whose host is a loopback literal or the name localhost, with no user information; returns it normalised. */
    public static URI loopbackOnly(String baseUrl) {
        URI uri;
        try {
            uri = URI.create(baseUrl == null ? "" : baseUrl.strip());
        } catch (IllegalArgumentException invalid) {
            throw new IllegalStateException("nexus.ai.ollama.base-url is not a URL");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost();
        if (!(scheme.equals("http") || scheme.equals("https")) || host == null || uri.getUserInfo() != null || uri.getRawAuthority() == null)
            throw new IllegalStateException("nexus.ai.ollama.base-url must be an http(s) URL without user information");
        String h = host.toLowerCase(Locale.ROOT);
        boolean loopback = h.equals("localhost") || h.equals("[::1]") || h.equals("::1") || isLoopbackIpv4(h);
        if (!loopback) throw new IllegalStateException("nexus.ai.ollama.base-url must be a loopback address (localhost, 127.x.x.x or ::1): device evidence is never sent to another machine by configuration");
        if (uri.getRawPath() != null && !uri.getRawPath().isEmpty() && !uri.getRawPath().equals("/")) throw new IllegalStateException("nexus.ai.ollama.base-url must not contain a path");
        return URI.create(scheme + "://" + (h.contains(":") && !h.startsWith("[") ? "[" + h + "]" : h) + (uri.getPort() > 0 ? ":" + uri.getPort() : ""));
    }

    private static boolean isLoopbackIpv4(String h) {
        if (!h.matches("127(\\.(25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])){3}")) return false;
        return true;
    }

    /** A model name that selects Ollama's remotely hosted offering (for example qwen3.5:cloud or a name ending in -cloud). */
    public static boolean namesRemoteModel(String model) {
        String m = model.toLowerCase(Locale.ROOT);
        return m.endsWith(":cloud") || m.endsWith("-cloud") || m.contains(":cloud-") || m.contains("-cloud:");
    }

    @Override
    public ProviderIdentity identity() {
        return identity;
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public String complete(String prompt, int maxOutputChars) throws ModelProviderException {
        if (isRemote()) throw new ModelProviderException("REMOTE_MODEL_REFUSED", false);
        Map<String, Object> body = Map.of("model", model, "stream", false, "format", "json",
                "options", Map.of("temperature", 0, "seed", 0, "num_predict", maxOutputTokens),
                "messages", List.of(Map.of("role", "user", "content", prompt)));
        try {
            HttpRequest request = HttpRequest.newBuilder(chatUri).timeout(requestTimeout).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body), StandardCharsets.UTF_8)).build();
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            int status = response.statusCode();
            String text = readCapped(response.body());
            if (status == 404) throw new ModelProviderException("MODEL_NOT_FOUND", false);
            if (status >= 500) throw new ModelProviderException("HTTP_5XX", true);
            if (status != 200) throw new ModelProviderException("HTTP_" + status, false);
            JsonNode json = MAPPER.readTree(text);
            JsonNode content = json == null ? null : json.path("message").path("content");
            if (content == null || !content.isTextual() || content.asText().isBlank()) throw new ModelProviderException("NO_CONTENT", false);
            return content.asText();
        } catch (ModelProviderException e) {
            throw e;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new ModelProviderException("BAD_RESPONSE", false);
        } catch (HttpTimeoutException e) {
            throw new ModelProviderException("TIMEOUT", true);
        } catch (ConnectException e) {
            throw new ModelProviderException("NETWORK", true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ModelProviderException("INTERRUPTED", false);
        } catch (IOException e) {
            throw new ModelProviderException("NETWORK", true);
        } catch (RuntimeException e) {
            throw new ModelProviderException("CLIENT_ERROR", false);
        }
    }

    private static String readCapped(InputStream in) throws IOException, ModelProviderException {
        try (in) {
            byte[] bytes = in.readNBytes(MAX_RESPONSE_BYTES + 1);
            if (bytes.length > MAX_RESPONSE_BYTES) throw new ModelProviderException("RESPONSE_TOO_LARGE", false);
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    /** One-time check of the server's own description of the model: a model that Ollama forwards to a hosted service is never used. Unknown means not remote. */
    private boolean isRemote() {
        Boolean known = remoteModel.get();
        if (known != null) return known;
        boolean remote = false;
        try {
            HttpRequest request = HttpRequest.newBuilder(showUri).timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(Map.of("model", model)), StandardCharsets.UTF_8)).build();
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            String text = readCapped(response.body());
            if (response.statusCode() == 200) {
                JsonNode json = MAPPER.readTree(text);
                remote = json.hasNonNull("remote_host") || json.hasNonNull("remote_model");
                remoteModel.set(remote);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException | ModelProviderException | RuntimeException unknown) {
            // the server could not describe the model now; the chat call that follows reports the real problem
        }
        return remote;
    }
}
