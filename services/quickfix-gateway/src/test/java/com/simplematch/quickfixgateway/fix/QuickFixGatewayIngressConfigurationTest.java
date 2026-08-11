package com.simplematch.quickfixgateway.fix;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(
    properties = {
      "simplematch.quickfix-gateway.acceptor-enabled=false",
      "simplematch.quickfix-gateway.data-plane-enabled=false",
      "simplematch.quickfix-gateway.replay-enabled=false",
      "simplematch.quickfix-gateway.operations.monitor-enabled=false",
      "spring.kafka.listener.auto-startup=false",
      "spring.main.web-application-type=none"
    })
@ActiveProfiles("test")
class QuickFixGatewayIngressConfigurationTest {
  @Autowired private ApplicationContext applicationContext;

  @DisplayName("Spring composes both durable paths behind the two-handler ingress dispatcher")
  @Test
  void springComposesTheFinalIngressSeam() {
    assertThat(applicationContext.getBean("newOrderFixMessageHandler"))
        .isInstanceOf(NewOrderFixMessageHandler.class);
    assertThat(applicationContext.getBean("cancelOrderFixMessageHandler"))
        .isInstanceOf(CancelOrderFixMessageHandler.class);
    assertThat(applicationContext.getBean(InboundFixMessageHandler.class)).isNotNull();
    assertThat(applicationContext.getBean(QuickFixApplicationAdapter.class)).isNotNull();

    assertThat(InboundFixMessageHandler.class.getDeclaredConstructors())
        .hasSize(1)
        .allSatisfy(constructor -> assertThat(constructor.getParameterCount()).isEqualTo(2));
    assertThat(NewOrderFixMessageHandler.class.getDeclaredConstructors())
        .hasSize(1)
        .allSatisfy(constructor -> assertThat(constructor.getParameterCount()).isLessThanOrEqualTo(7));
  }
}
