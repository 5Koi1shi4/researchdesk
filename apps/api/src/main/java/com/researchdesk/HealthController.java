package com.researchdesk;

import java.util.Map;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.health.Status;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class HealthController {
    private final HealthEndpoint healthEndpoint;

    HealthController(HealthEndpoint healthEndpoint) {
        this.healthEndpoint = healthEndpoint;
    }

    @GetMapping("/api/health")
    Map<String, String> health() {
        return Map.of("status", "UP");
    }

    @GetMapping("/api/ready")
    ResponseEntity<Map<String, String>> ready() {
        var status = healthEndpoint.healthForPath("readiness").getStatus();
        boolean available = Status.UP.equals(status);
        return ResponseEntity.status(available ? 200 : 503)
            .body(Map.of("status", available ? "UP" : "DOWN"));
    }
}
