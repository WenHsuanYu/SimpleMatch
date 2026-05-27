package com.simplematch.riskservice;

import static org.assertj.core.api.Assertions.assertThat;

import com.simplematch.config.SimpleMatchConfig;
import com.simplematch.riskservice.bootstrap.RiskServiceRuntime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(
    properties = {
  "simplematch.postgres.dsn=jdbc:h2:mem:risk-context;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;INIT=CREATE SCHEMA IF NOT EXISTS risk_service\\;SET SCHEMA risk_service",
        "simplematch.risk-service.grpc.enabled=false",
        "spring.main.web-application-type=none"
    })
class RiskServiceApplicationTest {
  @Autowired
  private SimpleMatchConfig simpleMatchConfig;

  @Autowired
  private RiskServiceRuntime runtime;

  // Verify that risk-service loads shared config, overrides the database DSN, and creates its runtime on startup.
  // Scenario: start the Spring context with gRPC disabled and confirm the default environment, gRPC port, and test database DSN.
  @DisplayName("risk-service loads shared config and runtime on startup")
  @Test
  void contextLoadsWithSharedConfig() {
    assertThat(simpleMatchConfig.getEnv()).isEqualTo("dev");
    assertThat(runtime.grpcPort()).isEqualTo(50052);
    assertThat(simpleMatchConfig.getPostgres().getDsn()).isEqualTo(
        "jdbc:h2:mem:risk-context;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;INIT=CREATE SCHEMA IF NOT EXISTS risk_service;SET SCHEMA risk_service");
  }
}