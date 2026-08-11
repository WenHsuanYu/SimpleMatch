package com.simplematch.quickfixgateway.config;

import com.simplematch.config.delivery.CriticalDeliveryController;
import com.simplematch.config.delivery.DeliveryMetrics;
import com.simplematch.config.delivery.MicrometerDeliveryMetrics;
import com.simplematch.config.delivery.QuarantineStore;
import com.simplematch.quickfixgateway.fix.FinalFixDeliveryDispatcher;
import com.simplematch.quickfixgateway.fix.FinalFixExecutionReportMapper;
import com.simplematch.quickfixgateway.fix.FixSessionMessageSender;
import com.simplematch.quickfixgateway.fix.OrderSessionRegistry;
import com.simplematch.quickfixgateway.kafka.FinalMatchingEventFixConsumer;
import com.simplematch.quickfixgateway.kafka.JdbcQuickFixFinalMatchingEventQuarantineStore;
import com.simplematch.quickfixgateway.matching.FinalMatchingEventFixDeliveryApplicationService;
import com.simplematch.quickfixgateway.matching.FinalMatchingEventFixDeliveryHandler;
import com.simplematch.quickfixgateway.matching.FinalMatchingEventFixDeliveryPlanner;
import com.simplematch.quickfixgateway.matching.QuickFixFinalMatchingEventStatus;
import com.simplematch.quickfixgateway.matching.QuickFixFinalMatchingEventsHealthIndicator;
import com.simplematch.quickfixgateway.store.JdbcFinalFixDeliveryStore;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Wires the Gateway's critical final-event inbox, durable ledger, and at-least-once dispatcher. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(QuickFixGatewayFinalMatchingEventConsumerProperties.class)
@ConditionalOnProperty(
    name = "simplematch.quickfix-gateway.data-plane-enabled",
    havingValue = "true",
    matchIfMissing = true)
public class QuickFixGatewayFinalMatchingEventConfiguration {
  /** Creates the Gateway-owned final-event inbox and FIX delivery ledger adapter. */
  @Bean
  JdbcFinalFixDeliveryStore jdbcFinalFixDeliveryStore(JdbcTemplate jdbcTemplate) {
    return new JdbcFinalFixDeliveryStore(jdbcTemplate);
  }

  /** Creates final-event-to-FIX planning with strict owning-session validation. */
  @Bean
  FinalMatchingEventFixDeliveryPlanner finalMatchingEventFixDeliveryPlanner(
      OrderSessionRegistry orderSessionRegistry) {
    return new FinalMatchingEventFixDeliveryPlanner(orderSessionRegistry);
  }

  /** Creates the one-transaction final-event acceptance boundary. */
  @Bean
  FinalMatchingEventFixDeliveryHandler finalMatchingEventFixDeliveryHandler(
      JdbcFinalFixDeliveryStore store,
      FinalMatchingEventFixDeliveryPlanner planner,
      Clock quickFixGatewayClock) {
    return new FinalMatchingEventFixDeliveryApplicationService(
        store, planner, quickFixGatewayClock);
  }

  /** Creates exact raw-byte evidence storage for a blocked Gateway final-event partition. */
  @Bean
  QuarantineStore quickFixFinalMatchingEventQuarantineStore(JdbcTemplate jdbcTemplate) {
    return new JdbcQuickFixFinalMatchingEventQuarantineStore(jdbcTemplate);
  }

  /**
   * Creates strict retry, quarantine, and same-offset recovery policy for critical FIX delivery.
   */
  @Bean
  CriticalDeliveryController quickFixFinalMatchingEventDeliveryController(
      QuickFixGatewayFinalMatchingEventConsumerProperties properties,
      Clock quickFixGatewayClock,
      QuarantineStore quickFixFinalMatchingEventQuarantineStore,
      ObjectProvider<MeterRegistry> meterRegistryProvider) {
    final MeterRegistry meterRegistry = meterRegistryProvider.getIfAvailable();
    final DeliveryMetrics metrics =
        meterRegistry == null
            ? DeliveryMetrics.noop()
            : new MicrometerDeliveryMetrics(meterRegistry);
    return new CriticalDeliveryController(
        "quickfix-final-matching-events",
        properties.maximumAttempts(),
        properties.recoveryInstructions(),
        quickFixGatewayClock,
        quickFixFinalMatchingEventQuarantineStore,
        metrics);
  }

  /** Creates the compact consumer status used by readiness and Gateway admission composition. */
  @Bean
  QuickFixFinalMatchingEventStatus quickFixFinalMatchingEventStatus() {
    return new QuickFixFinalMatchingEventStatus();
  }

  /** Creates the manual-ack Kafka boundary for final Matching Event delivery. */
  @Bean
  FinalMatchingEventFixConsumer finalMatchingEventFixConsumer(
      FinalMatchingEventFixDeliveryHandler handler,
      CriticalDeliveryController quickFixFinalMatchingEventDeliveryController,
      QuickFixFinalMatchingEventStatus status) {
    return new FinalMatchingEventFixConsumer(
        handler, quickFixFinalMatchingEventDeliveryController, status);
  }

  /** Creates a readiness indicator that becomes unavailable only for an actual quarantine. */
  @Bean("quickFixFinalMatchingEventsReadyHealthIndicator")
  QuickFixFinalMatchingEventsHealthIndicator quickFixFinalMatchingEventsReadyHealthIndicator(
      QuickFixFinalMatchingEventStatus status) {
    return new QuickFixFinalMatchingEventsHealthIndicator(status);
  }

  /** Creates the serial at-least-once sender that can replay every still-pending report intent. */
  @Bean
  FinalFixDeliveryDispatcher finalFixDeliveryDispatcher(
      JdbcFinalFixDeliveryStore store,
      FixSessionMessageSender sender,
      OrderSessionRegistry orderSessionRegistry,
      QuickFixGatewayFinalMatchingEventConsumerProperties properties,
      Clock quickFixGatewayClock) {
    return new FinalFixDeliveryDispatcher(
        store,
        new FinalFixExecutionReportMapper(),
        sender,
        orderSessionRegistry,
        quickFixGatewayClock,
        properties.deliveryBatchSize());
  }
}
