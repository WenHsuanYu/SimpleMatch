package com.simplematch.quickfixgateway.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simplematch.config.delivery.DeadLetterStore;
import com.simplematch.config.delivery.DeliveryMetrics;
import com.simplematch.config.delivery.MicrometerDeliveryMetrics;
import com.simplematch.config.delivery.NonCriticalDeliveryController;
import com.simplematch.quickfixgateway.fix.ExecutionSessionResolver;
import com.simplematch.quickfixgateway.fix.FixMessageMapper;
import com.simplematch.quickfixgateway.fix.FixSessionMessageSender;
import com.simplematch.quickfixgateway.fix.OrderSessionRegistry;
import com.simplematch.quickfixgateway.kafka.KafkaDeadLetterStore;
import com.simplematch.quickfixgateway.kafka.MatchingExecutionConsumer;
import com.simplematch.quickfixgateway.kafka.NonCriticalExecutionProjectionConsumer;
import com.simplematch.quickfixgateway.kafka.NonCriticalRetryScheduler;
import com.simplematch.quickfixgateway.kafka.ScheduledNonCriticalRetryScheduler;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;

/** Wires the rebuildable QuickFIX execution projection and its non-critical delivery policy. */
@Configuration(proxyBeanMethods = false)
public class QuickFixGatewayExecutionProjectionConfiguration {
  @Bean
  @ConditionalOnProperty(
      name = "simplematch.quickfix-gateway.data-plane-enabled",
      havingValue = "true",
      matchIfMissing = true)
  MatchingExecutionConsumer matchingExecutionConsumer(
      ExecutionSessionResolver executionSessionResolver,
      OrderSessionRegistry orderSessionRegistry,
      FixMessageMapper fixMessageMapper,
      FixSessionMessageSender fixSessionMessageSender) {
    return new MatchingExecutionConsumer(
        executionSessionResolver, orderSessionRegistry, fixMessageMapper, fixSessionMessageSender);
  }

  @Bean
  @ConditionalOnProperty(
      name = "simplematch.quickfix-gateway.data-plane-enabled",
      havingValue = "true",
      matchIfMissing = true)
  DeadLetterStore quickFixExecutionDeadLetterStore(
      KafkaTemplate<String, byte[]> kafkaTemplate,
      QuickFixGatewayExecutionProjectionProperties properties) {
    return new KafkaDeadLetterStore(
        kafkaTemplate, new ObjectMapper(), properties.deadLetterTopic());
  }

  @Bean
  @ConditionalOnProperty(
      name = "simplematch.quickfix-gateway.data-plane-enabled",
      havingValue = "true",
      matchIfMissing = true)
  NonCriticalDeliveryController quickFixExecutionDeliveryController(
      QuickFixGatewayExecutionProjectionProperties properties,
      Clock quickFixGatewayClock,
      DeadLetterStore deadLetterStore,
      ObjectProvider<MeterRegistry> meterRegistryProvider) {
    final MeterRegistry meterRegistry = meterRegistryProvider.getIfAvailable();
    final DeliveryMetrics metrics =
        meterRegistry == null
            ? DeliveryMetrics.noop()
            : new MicrometerDeliveryMetrics(meterRegistry);
    return new NonCriticalDeliveryController(
        "quickfix-execution-projection",
        properties.maximumAttempts(),
        Duration.ofMillis(properties.retryDelayMillis()),
        quickFixGatewayClock,
        deadLetterStore,
        metrics);
  }

  @Bean(destroyMethod = "close")
  @ConditionalOnProperty(
      name = "simplematch.quickfix-gateway.data-plane-enabled",
      havingValue = "true",
      matchIfMissing = true)
  ScheduledNonCriticalRetryScheduler quickFixExecutionRetryScheduler() {
    return new ScheduledNonCriticalRetryScheduler();
  }

  @Bean
  @ConditionalOnProperty(
      name = "simplematch.quickfix-gateway.data-plane-enabled",
      havingValue = "true",
      matchIfMissing = true)
  NonCriticalExecutionProjectionConsumer matchingExecutionProjectionConsumer(
      MatchingExecutionConsumer projection,
      NonCriticalDeliveryController deliveryController,
      NonCriticalRetryScheduler retryScheduler) {
    return new NonCriticalExecutionProjectionConsumer(
        projection::onExecution, deliveryController, retryScheduler);
  }
}
