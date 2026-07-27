package com.simplematch.quickfixgateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.simplematch.quickfixgateway.config.QuickFixGatewayProperties;
import com.simplematch.quickfixgateway.config.QuickFixGatewayRuntime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(
    properties = {
    "simplematch.quickfix-gateway.acceptor-enabled=false",
  "simplematch.quickfix-gateway.data-plane-enabled=false",
  "simplematch.quickfix-gateway.replay-enabled=false",
    "spring.kafka.listener.auto-startup=false",
        "spring.main.web-application-type=none"
    })
@ActiveProfiles("test")
class QuickFixGatewayApplicationTest {
  @Autowired
  private QuickFixGatewayProperties gatewayProperties;

  @Autowired
  private QuickFixGatewayRuntime runtime;

  @Autowired
  private Environment environment;

  // Verify that quickfix-gateway loads its runtime and required path settings on startup.
  // Scenario: start the Spring context with the acceptor, data plane, and replay disabled, then confirm the settings are bound correctly.
  @DisplayName("quickfix-gateway loads runtime and path settings on startup")
  @Test
  void contextLoadsWithQuickFixRuntime() {
    assertThat(gatewayProperties.quickfixConfigPath()).isEqualTo("config/quickfix/acceptor.cfg");
    assertThat(runtime.quickfixConfigPath().toString()).endsWith("config/quickfix/acceptor.cfg");
    assertThat(runtime.walPath().toString()).endsWith("data/quickfix/wal/inbound.wal");
    assertThat(gatewayProperties.ownerId()).isEqualTo("quickfix-gateway-0");
    assertThat(runtime.ownerId()).isEqualTo("quickfix-gateway-0");
    assertThat(environment.getProperty("spring.kafka.consumer.group-id")).isEqualTo("quickfix-gateway-0");
  }
}
