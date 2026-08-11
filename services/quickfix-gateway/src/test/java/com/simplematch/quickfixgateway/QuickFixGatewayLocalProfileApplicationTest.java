package com.simplematch.quickfixgateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.simplematch.config.EnvironmentProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(
    properties = {
      "simplematch.postgres.dsn=jdbc:h2:mem:quickfixlocal;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;INIT=CREATE SCHEMA IF NOT EXISTS quickfix_gateway\\;SET SCHEMA quickfix_gateway",
      "simplematch.quickfix-gateway.acceptor-enabled=false",
      "simplematch.quickfix-gateway.data-plane-enabled=false",
      "simplematch.quickfix-gateway.replay-enabled=false",
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
