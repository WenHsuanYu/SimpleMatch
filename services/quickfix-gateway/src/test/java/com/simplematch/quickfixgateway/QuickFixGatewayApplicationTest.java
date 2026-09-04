package com.simplematch.quickfixgateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.simplematch.config.GrpcProperties;
import com.simplematch.config.KafkaProperties;
import com.simplematch.quickfixgateway.config.QuickFixGatewayFileProperties;
import com.simplematch.quickfixgateway.config.QuickFixGatewayRuntime;
import com.simplematch.quickfixgateway.config.QuickFixGatewayRuntimeProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(
    properties = {
      "simplematch.postgres.dsn=jdbc:h2:mem:quickfixcontext;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;INIT=CREATE SCHEMA IF NOT EXISTS quickfix_gateway\\;SET SCHEMA quickfix_gateway",
      "simplematch.quickfix-gateway.acceptor-enabled=false",
      "simplematch.quickfix-gateway.data-plane-enabled=false",
      "simplematch.quickfix-gateway.replay-enabled=false",
      "spring.flyway.enabled=false",
      "spring.kafka.listener.auto-startup=false",
      "spring.task.scheduling.enabled=false",
      "simplematch.quickfix-gateway.operations.monitor-enabled=false",
      "spring.main.web-application-type=none"
    })
@ActiveProfiles("test")
class QuickFixGatewayApplicationTest {
  @Autowired private QuickFixGatewayFileProperties fileProperties;

  @Autowired private QuickFixGatewayRuntimeProperties runtimeProperties;

  @Autowired private QuickFixGatewayRuntime runtime;

  @Autowired private GrpcProperties grpcProperties;

  @Autowired private KafkaProperties kafkaProperties;

  @Autowired private Environment environment;

  // Verify that quickfix-gateway loads its runtime and required path settings on startup.
  // Scenario: start the Spring context with the acceptor, data plane, and replay disabled, then
  // confirm the settings are bound correctly.
  @DisplayName("quickfix-gateway loads runtime and path settings on startup")
  @Test
  void contextLoadsWithQuickFixRuntime() {
    assertThat(fileProperties.quickfixConfigPath()).isEqualTo("config/quickfix/acceptor.cfg");
    assertThat(runtime.quickfixConfigPath().toString()).endsWith("config/quickfix/acceptor.cfg");
    assertThat(runtime.walPath().toString()).endsWith("data/quickfix/wal/inbound.wal");
    assertThat(runtimeProperties.ownerId()).isEqualTo("quickfix-gateway-0");
    assertThat(runtime.ownerId()).isEqualTo("quickfix-gateway-0");
    assertThat(grpcProperties.targets().riskService()).isEqualTo("dns:///risk-service:50052");
    assertThat(kafkaProperties.topics().matchingEvents()).isEqualTo("matching.events");
    assertThat(environment.getProperty("simplematch.postgres.dsn"))
        .contains("jdbc:h2:mem:quickfixcontext");
    assertThat(environment.getProperty("spring.kafka.consumer.group-id"))
        .isEqualTo("quickfix-gateway-0");
  }
}
