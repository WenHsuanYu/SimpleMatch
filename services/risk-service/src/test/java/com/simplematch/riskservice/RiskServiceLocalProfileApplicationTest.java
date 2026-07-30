package com.simplematch.riskservice;

import static org.assertj.core.api.Assertions.assertThat;

import com.simplematch.config.PlatformProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(
    properties = {
      "simplematch.postgres.dsn=jdbc:h2:mem:risk-local;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;INIT=CREATE SCHEMA IF NOT EXISTS risk_service\\;SET SCHEMA risk_service",
      "simplematch.risk-service.grpc.enabled=false",
      "spring.main.web-application-type=none"
    })
@ActiveProfiles("local")
class RiskServiceLocalProfileApplicationTest {
  @Autowired private PlatformProperties platformProperties;

  @Test
  void startsWithTheLocalProfile() {
    assertThat(platformProperties.environment()).isEqualTo("local");
  }
}
