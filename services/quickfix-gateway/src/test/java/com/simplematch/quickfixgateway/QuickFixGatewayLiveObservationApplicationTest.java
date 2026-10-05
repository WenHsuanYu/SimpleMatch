package com.simplematch.quickfixgateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simplematch.config.delivery.CriticalConsumerOperationalStatusProvider;
import com.simplematch.quickfixgateway.config.GatewayLiveObservationProperties;
import com.simplematch.quickfixgateway.matching.QuickFixFinalMatchingEventStatus;
import com.simplematch.quickfixgateway.operations.DeadlineTradingSystemObservationCollector;
import com.simplematch.quickfixgateway.operations.GatewayLiveObservationReporter;
import com.simplematch.quickfixgateway.operations.TradingSystemObservationCollector;
import com.simplematch.quickfixgateway.operations.live.JdkStatusDocumentClient;
import com.simplematch.quickfixgateway.operations.live.StatusDocumentClient;
import org.apache.kafka.clients.admin.Admin;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** Exercises live adapter composition without starting Kafka polling or the delivery data plane. */
@SpringBootTest(
    properties = {
      "simplematch.postgres.dsn=jdbc:h2:mem:quickfixlivecontext;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;INIT=CREATE SCHEMA IF NOT EXISTS quickfix_gateway\\;SET SCHEMA quickfix_gateway",
      "simplematch.quickfix-gateway.acceptor-enabled=false",
      "simplematch.quickfix-gateway.data-plane-enabled=false",
      "simplematch.quickfix-gateway.replay-enabled=false",
      "simplematch.quickfix-gateway.live-observation.enabled=true",
      "simplematch.quickfix-gateway.operations.monitor-enabled=false",
      "spring.flyway.enabled=false",
      "spring.kafka.listener.auto-startup=false",
      "spring.main.web-application-type=none"
    })
@ActiveProfiles("test")
@MockitoBean(name = "gatewayAdmissionKafkaAdmin", types = Admin.class)
@MockitoBean(
    types = {
      GatewayLiveObservationReporter.class,
      CriticalConsumerOperationalStatusProvider.class,
      QuickFixFinalMatchingEventStatus.class
    })
class QuickFixGatewayLiveObservationApplicationTest {
  @Autowired private ApplicationContext context;

  @Autowired private GatewayLiveObservationProperties liveProperties;

  @Autowired private StatusDocumentClient documents;

  @Autowired private TradingSystemObservationCollector collector;

  @Test
  void startsLiveAdaptersWithoutAFrameworkJacksonTwoMapper() {
    assertThat(liveProperties.enabled()).isTrue();
    assertThat(documents).isInstanceOf(JdkStatusDocumentClient.class);
    assertThat(collector).isInstanceOf(DeadlineTradingSystemObservationCollector.class);
    assertThat(context.getBeansOfType(ObjectMapper.class)).isEmpty();
  }
}
