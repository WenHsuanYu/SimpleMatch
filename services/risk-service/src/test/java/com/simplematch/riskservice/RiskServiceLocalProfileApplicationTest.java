package com.simplematch.riskservice;

import static com.simplematch.riskservice.testsupport.H2TestDatabaseUrl.riskServiceUrl;
import static org.assertj.core.api.Assertions.assertThat;

import com.simplematch.config.EnvironmentProperties;
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
      "simplematch.risk-service.market-reference.artifact-location=classpath:/market-reference/market_reference.json",
      "simplematch.risk-service.market-reference.checksum-location=classpath:/market-reference/market_reference.sha256",
      "simplematch.risk-service.market-reference.trading-day=2026-08-11",
      "simplematch.risk-service.market-reference.matching-image-digest=sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
      "spring.main.web-application-type=none"
    })
@ActiveProfiles("local")
class RiskServiceLocalProfileApplicationTest {
  @Autowired private EnvironmentProperties environmentProperties;

  @DynamicPropertySource
  static void configureDataSource(DynamicPropertyRegistry registry) {
    registry.add("simplematch.postgres.dsn", () -> riskServiceUrl("risklocal"));
  }

  @Test
  void startsWithTheLocalProfile() {
    assertThat(environmentProperties.environment()).isEqualTo("local");
  }
}
