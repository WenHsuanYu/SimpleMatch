package com.simplematch.quickfixgateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.simplematch.config.EnvironmentProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(
    properties = {
      "simplematch.postgres.dsn=postgresql://configuration.invalid:5432/quickfix_gateway",
      "simplematch.quickfix-gateway.acceptor-enabled=false",
      "simplematch.quickfix-gateway.data-plane-enabled=false",
      "simplematch.quickfix-gateway.replay-enabled=false",
      "simplematch.quickfix-gateway.ingress.trading-day=2026-08-11",
      "spring.flyway.enabled=false",
      "spring.kafka.listener.auto-startup=false",
      "spring.task.scheduling.enabled=false",
      "simplematch.quickfix-gateway.operations.monitor-enabled=false",
      "spring.main.web-application-type=none"
    })
@ActiveProfiles("local")
class QuickFixGatewayLocalProfileApplicationTest {
  @Autowired private EnvironmentProperties environmentProperties;

  @Test
  void startsWithTheLocalProfile() {
    assertThat(environmentProperties.environment()).isEqualTo("local");
  }
}
