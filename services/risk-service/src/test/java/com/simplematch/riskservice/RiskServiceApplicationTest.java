package com.simplematch.riskservice;

import static com.simplematch.riskservice.testsupport.H2TestDatabaseUrl.riskServiceUrl;
import static org.assertj.core.api.Assertions.assertThat;

import com.simplematch.config.PlatformProperties;
import com.simplematch.riskservice.bootstrap.RiskServiceRuntime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(
    properties = {
      "simplematch.risk-service.grpc.enabled=false",
      "simplematch.risk-service.scheduling-enabled=false",
      "spring.main.web-application-type=none"
    })
@ActiveProfiles("test")
class RiskServiceApplicationTest {
  @Autowired private PlatformProperties platformProperties;

  @Autowired private RiskServiceRuntime runtime;

  @DynamicPropertySource
  static void configureDataSource(DynamicPropertyRegistry registry) {
    registry.add("simplematch.postgres.dsn", () -> riskServiceUrl("riskcontext"));
  }

  // Verify that risk-service loads shared config, overrides the database DSN, and creates its
  // runtime on startup.
  // Scenario: start the Spring context with gRPC disabled and confirm the default environment, gRPC
  // port, and test database DSN.
  @DisplayName("risk-service loads shared config and runtime on startup")
  @Test
  void contextLoadsWithSharedConfig() {
    assertThat(platformProperties.environment()).isEqualTo("test");
    assertThat(runtime.grpcPort()).isEqualTo(50052);
    assertThat(platformProperties.postgres().dsn())
        .isEqualTo(
            riskServiceUrl("riskcontext"));
  }
}
