package com.nexus.rule.ai;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the Tier Frontend 2.0 Phase 1 base-URL configurability of
 * {@link AiConfiguration#aiRestClient}. No Spring context is started -
 * the bean method is a plain factory function, called directly.
 */
class AiConfigurationTest {

    private HttpServer server;
    private int port;
    private final AtomicInteger requestCount = new AtomicInteger(0);

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        port = server.getAddress().getPort();
        requestCount.set(0);
        server.createContext("/probe", exchange -> {
            requestCount.incrementAndGet();
            byte[] bytes = "{}".getBytes();
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void configuredBaseUrl_isHonored_requestsGoToTheConfiguredHost() {
        RestClient client = new AiConfiguration().aiRestClient(
                "http://localhost:" + port, "test-key", 5000);

        client.get().uri("/probe").retrieve().toBodilessEntity();

        assertEquals(1, requestCount.get(), "the request must have actually reached the locally-configured base URL");
    }

    @Test
    void baseUrlProperty_defaultsToOpenAi_whenNotSet() throws NoSuchMethodException {
        // Deterministic, non-network check: the @Value default on the
        // parameter must still be the exact prior hardcoded OpenAI URL, so
        // any deployment that doesn't set nexus.ai.base-url is unaffected.
        Method method = AiConfiguration.class.getMethod("aiRestClient", String.class, String.class, int.class);
        Parameter baseUrlParam = method.getParameters()[0];
        org.springframework.beans.factory.annotation.Value valueAnnotation =
                baseUrlParam.getAnnotation(org.springframework.beans.factory.annotation.Value.class);

        assertTrue(valueAnnotation != null, "the baseUrl parameter must carry a @Value default");
        assertEquals("${nexus.ai.base-url:https://api.openai.com/v1}", valueAnnotation.value());
    }
}
