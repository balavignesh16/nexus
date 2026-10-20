package com.nexus.intelligence.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Asks a LOCAL Ollama server what it actually has, so a model is chosen from facts and not from its name: whether the server answers, whether the model is
 * installed, its digest, size, parameter count, quantisation, the capabilities the server reports (for example completion and tools) and whether the model is
 * hosted remotely. Used by the evaluation and by operators; the gateway path does not depend on it. It only reads, and it accepts only a loopback base URL.
 */
public final class OllamaProbe {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** {@code supportsTools} is true only when the server lists the tools capability; absence is reported, not assumed. */
    public record Inspection(boolean reachable, String version, boolean installed, String digest, Long sizeBytes, String parameterSize, String quantization, String family, List<String> capabilities,
                             boolean supportsTools, boolean remotelyHosted, String problem) {}

    private OllamaProbe() {}

    public static Inspection inspect(String baseUrl, String model, Duration timeout) {
        URI base = OllamaModelProvider.loopbackOnly(baseUrl);
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();
        String version = null;
        try {
            JsonNode v = get(client, base.resolve("/api/version"), timeout);
            version = v.path("version").asText(null);
        } catch (Exception unreachable) {
            return new Inspection(false, null, false, null, null, null, null, null, List.of(), false, false, "the Ollama server did not answer on " + base.getHost() + ":" + base.getPort());
        }
        try {
            Long size = null;
            String digest = null;
            boolean installed = false;
            JsonNode tags = get(client, base.resolve("/api/tags"), timeout);
            for (JsonNode m : tags.path("models")) {
                if (model.equals(m.path("name").asText()) || model.equals(m.path("model").asText())) {
                    installed = true;
                    digest = m.path("digest").asText(null);
                    size = m.hasNonNull("size") ? m.get("size").asLong() : null;
                }
            }
            if (!installed) return new Inspection(true, version, false, null, null, null, null, null, List.of(), false, OllamaModelProvider.namesRemoteModel(model), "the model is not installed on this server");
            JsonNode show = post(client, base.resolve("/api/show"), Map.of("model", model), timeout);
            List<String> capabilities = new ArrayList<>();
            for (JsonNode c : show.path("capabilities")) capabilities.add(c.asText());
            JsonNode details = show.path("details");
            boolean remote = show.hasNonNull("remote_host") || show.hasNonNull("remote_model") || OllamaModelProvider.namesRemoteModel(model);
            return new Inspection(true, version, true, digest, size, details.path("parameter_size").asText(null), details.path("quantization_level").asText(null), details.path("family").asText(null),
                    capabilities, capabilities.contains("tools"), remote, null);
        } catch (Exception failed) {
            return new Inspection(true, version, false, null, null, null, null, null, List.of(), false, false, "the server could not describe the model (" + failed.getClass().getSimpleName() + ")");
        }
    }

    private static JsonNode get(HttpClient client, URI uri, Duration timeout) throws Exception {
        HttpResponse<InputStream> r = client.send(HttpRequest.newBuilder(uri).timeout(timeout).GET().build(), HttpResponse.BodyHandlers.ofInputStream());
        return read(r);
    }

    private static JsonNode post(HttpClient client, URI uri, Object body, Duration timeout) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(timeout).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body), StandardCharsets.UTF_8)).build();
        return read(client.send(request, HttpResponse.BodyHandlers.ofInputStream()));
    }

    private static JsonNode read(HttpResponse<InputStream> r) throws Exception {
        try (InputStream in = r.body()) {
            byte[] bytes = in.readNBytes(OllamaModelProvider.MAX_RESPONSE_BYTES + 1);
            if (bytes.length > OllamaModelProvider.MAX_RESPONSE_BYTES || r.statusCode() != 200) throw new IllegalStateException("unusable response");
            return MAPPER.readTree(new String(bytes, StandardCharsets.UTF_8));
        }
    }
}
