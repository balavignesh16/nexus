package com.nexus.intelligence.model;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** P6-010: the HTTP provider maps every outcome to fixed codes and never echoes response text. */
class OpenAiModelProviderTest {
    private final RestClient.Builder builder = RestClient.builder().baseUrl("http://provider.test/v1");
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final OpenAiModelProvider provider = new OpenAiModelProvider(builder.build(), "m-1", 256, "http://provider.test/v1");

    @Test
    void p6_010_aSuccessfulReplyIsReturnedAsTextAndTheRequestIsDeterministicJsonMode() throws Exception {
        server.expect(requestTo("http://provider.test/v1/chat/completions")).andExpect(method(HttpMethod.POST))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"json_object\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"temperature\":0")))
                .andRespond(withSuccess("{\"choices\":[{\"message\":{\"content\":\"{\\\"summary\\\":\\\"ok\\\"}\"}}]}", MediaType.APPLICATION_JSON));
        assertThat(provider.complete("prompt", 1000)).isEqualTo("{\"summary\":\"ok\"}");
        assertThat(provider.identity().modelId()).isEqualTo("m-1");
    }

    @Test
    void p6_010_failuresMapToFixedCodesAndOnlyServerAndNetworkErrorsAreTransient() {
        server.expect(requestTo("http://provider.test/v1/chat/completions")).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).body("secret-token-xyz oops"));
        ModelProviderException e5 = catchThrowableOfType(() -> provider.complete("p", 100), ModelProviderException.class);
        assertThat(e5.transientFailure()).isTrue();
        assertThat(e5.getMessage()).isEqualTo("HTTP_5XX").doesNotContain("secret-token-xyz");
        server.reset();
        server.expect(requestTo("http://provider.test/v1/chat/completions")).andRespond(withStatus(HttpStatus.UNAUTHORIZED).body("bad key sk-123"));
        ModelProviderException e4 = catchThrowableOfType(() -> provider.complete("p", 100), ModelProviderException.class);
        assertThat(e4.transientFailure()).isFalse();
        assertThat(e4.getMessage()).isEqualTo("HTTP_401").doesNotContain("sk-123");
        server.reset();
        server.expect(requestTo("http://provider.test/v1/chat/completions")).andRespond(withSuccess("{\"choices\":[]}", MediaType.APPLICATION_JSON));
        assertThat(catchThrowableOfType(() -> provider.complete("p", 100), ModelProviderException.class).getMessage()).isEqualTo("NO_CONTENT");
    }
}
