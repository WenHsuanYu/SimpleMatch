package com.simplematch.quickfixgateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.simplematch.quickfixgateway.health.QuickFixGatewayStartupRecovery;
import com.simplematch.quickfixgateway.kafka.MatchingExecutionConsumer;
import com.simplematch.quickfixgateway.kafka.NoopOrdersCommandPublisher;
import com.simplematch.quickfixgateway.kafka.OrdersCommandPublisher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

@SpringBootTest(
    properties = {
      "simplematch.quickfix-gateway.acceptor-enabled=false",
      "spring.kafka.listener.auto-startup=false",
      "spring.main.web-application-type=none"
    })
class QuickFixGatewayCompatibilityPublishDefaultTest {
  @Autowired private ApplicationContext applicationContext;

  @Autowired private OrdersCommandPublisher ordersCommandPublisher;

  @Autowired private MatchingExecutionConsumer matchingExecutionConsumer;

  @Autowired private QuickFixGatewayStartupRecovery startupRecovery;

  @DisplayName(
      "compatibility publish is disabled by default, but the matching executions consume path remains available")
  @Test
  void compatibilityPublishIsDisabledByDefault() {
    assertThat(ordersCommandPublisher).isInstanceOf(NoopOrdersCommandPublisher.class);
    assertThat(matchingExecutionConsumer).isNotNull();
    assertThat(applicationContext.getBeansOfType(QuickFixGatewayStartupRecovery.class)).hasSize(1);
    assertThat(startupRecovery.recover()).isZero();
  }
}
