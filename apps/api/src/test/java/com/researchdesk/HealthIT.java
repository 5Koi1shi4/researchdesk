package com.researchdesk;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,properties="INTERNAL_SERVICE_TOKEN=health-test-internal-token-32-characters")
class HealthIT {
    @Autowired TestRestTemplate http;

    @Test void livenessDoesNotDependOnDatabaseOrQueue() {
        var result = http.getForEntity("/api/health", String.class);
        assertThat(result.getStatusCode().value()).isEqualTo(200);
        assertThat(result.getBody()).isEqualTo("{\"status\":\"UP\"}");
    }

    @Test void readinessReflectsRealDatabaseAndQueue() {
        int expected = Integer.parseInt(System.getenv().getOrDefault("EXPECTED_READINESS", "503"));
        assertThat(http.getForEntity("/api/ready", String.class).getStatusCode().value()).isEqualTo(expected);
    }
}
