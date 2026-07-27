package com.nexus;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
    "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration"
})
class NexusApplicationTests {

	@Test
	void contextLoads() {
		// Verifies that the Spring application context can initialize properly
		// Excludes database auto-configuration to ensure deterministic execution without external dependencies
	}

}
