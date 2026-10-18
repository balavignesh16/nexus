package com.nexus.intelligence.model;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

/** P6-006: by default (no property) no external provider exists and the gateway refuses; a real provider needs nexus.ai.enabled=true. */
class ModelWiringTest {
    @Configuration
    @Import({ModelConfiguration.class, NoOpModelProvider.class, OpenAiModelProvider.class})
    static class Wiring {
        @Bean
        RestClient aiRestClient() {
            return RestClient.builder().baseUrl("http://localhost:1").build();
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Wiring.class);

    @Test
    void p6_007_byDefaultOnlyTheOfflineProviderExistsAndTheGatewayRefuses() {
        runner.run(ctx -> {
            assertThat(ctx).hasSingleBean(ModelProvider.class);
            assertThat(ctx.getBean(ModelProvider.class)).isInstanceOf(NoOpModelProvider.class);
            assertThat(ctx).doesNotHaveBean(OpenAiModelProvider.class);
            ModelOutcome o = ctx.getBean(ModelGateway.class).ask(new ModelRequest(ModelPurpose.EXPLANATION, "x", java.util.List.of(),
                    StructuredOutput.Schema.of(java.util.Map.of("a", StructuredOutput.FieldType.STRING), java.util.Set.of())));
            assertThat(((ModelOutcome.Refused) o).code()).isEqualTo(RefusalCode.DISABLED);
        });
    }

    @Test
    void p6_008_theRealProviderExistsOnlyWhenExplicitlyEnabled() {
        runner.withPropertyValues("nexus.ai.enabled=true").run(ctx -> {
            assertThat(ctx).hasSingleBean(ModelProvider.class);
            assertThat(ctx.getBean(ModelProvider.class)).isInstanceOf(OpenAiModelProvider.class);
            assertThat(ctx.getBean(ModelProvider.class).identity().providerId()).isEqualTo("openai-compatible");
        });
    }

    @Test
    void p6_010_anInvalidGuardSettingStopsStartup() {
        runner.withPropertyValues("nexus.ai.guard.max-retries=7").run(ctx -> assertThat(ctx).hasFailed());
    }
}
