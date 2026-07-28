package com.simplematch.quickfixgateway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "simplematch.quickfix-gateway.acceptor-enabled=false",
                "simplematch.quickfix-gateway.data-plane-enabled=false",
                "simplematch.quickfix-gateway.replay-enabled=false",
                "spring.kafka.listener.auto-startup=false"
        })
class QuickFixGatewayReadinessEndpointTest {
    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @DisplayName("the gateway exposes healthz and readyz actuator endpoints")
    @Test
    void gatewayExposesHealthzAndReadyzActuatorEndpoints() {
        final ResponseEntity<String> healthResponse = restTemplate.getForEntity(baseUrl() + "/healthz", String.class);
        final ResponseEntity<String> readinessResponse = restTemplate.getForEntity(baseUrl() + "/readyz", String.class);
        final ResponseEntity<String> metricsResponse = restTemplate.getForEntity(baseUrl() + "/metrics", String.class);

        assertThat(healthResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(healthResponse.getBody()).contains("UP");
        assertThat(readinessResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(readinessResponse.getBody()).contains("UP");
        assertThat(metricsResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(metricsResponse.getBody()).contains("names");
    }

    private String baseUrl() {
        return "http://localhost:" + port;
    }
}