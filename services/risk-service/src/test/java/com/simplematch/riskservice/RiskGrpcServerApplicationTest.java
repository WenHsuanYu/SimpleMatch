package com.simplematch.riskservice;

import static com.simplematch.riskservice.testsupport.H2TestDatabaseUrl.riskServiceUrl;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.SmartLifecycle;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(
    properties = {
      "simplematch.risk-service.grpc.enabled=true",
      "simplematch.risk-service.scheduling-enabled=false",
      "simplematch.risk-service.market-reference.artifact-location=classpath:/market-reference/market_reference.json",
      "simplematch.risk-service.market-reference.checksum-location=classpath:/market-reference/market_reference.sha256",
      "simplematch.risk-service.market-reference.trading-day=2026-08-11",
      "simplematch.risk-service.market-reference.matching-image-digest=sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
      "simplematch.grpc.targets.risk-service=dns:///risk-service:0",
      "spring.main.web-application-type=none"
    })
@ActiveProfiles("test")
class RiskGrpcServerApplicationTest {
  @Autowired
  @Qualifier("grpcServerLifecycle")
  private SmartLifecycle grpcServerLifecycle;

  @DynamicPropertySource
  static void configureDataSource(DynamicPropertyRegistry registry) {
    registry.add("simplematch.postgres.dsn", () -> riskServiceUrl("riskgrpc"));
  }

  @Test
  void startsProductionV2GrpcServerWithoutLegacyAdmissionService() {
    assertThat(grpcServerLifecycle.isRunning()).isTrue();
  }
}
