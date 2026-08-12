package com.simplematch.marketdataprojection.config;

import com.simplematch.config.delivery.DeadLetterStore;
import com.simplematch.config.delivery.DeliveryMetrics;
import com.simplematch.config.delivery.MicrometerDeliveryMetrics;
import com.simplematch.config.delivery.NonCriticalDeliveryController;
import com.simplematch.marketdataprojection.kafka.JdbcMarketDataProjectionDeadLetterStore;
import com.simplematch.marketdataprojection.kafka.MarketDataProjectionConsumer;
import com.simplematch.marketdataprojection.kafka.MarketDataProjectionRetryScheduler;
import com.simplematch.marketdataprojection.kafka.ScheduledMarketDataProjectionRetryScheduler;
import com.simplematch.marketdataprojection.runtime.MarketDataProjectionConsumerControl;
import com.simplematch.marketdataprojection.runtime.MarketDataProjectionHandler;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;

/** Wires the projection's isolated non-critical Kafka consumer and retry boundary. */
@Configuration(proxyBeanMethods = false)
public class MarketDataProjectionConsumerConfiguration {
  /** Persists durable evidence when the bounded non-critical retry budget is exhausted. */
  @Bean
  DeadLetterStore marketDataProjectionDeadLetterStore(
      JdbcTemplate marketDataProjectionJdbcTemplate) {
    return new JdbcMarketDataProjectionDeadLetterStore(marketDataProjectionJdbcTemplate);
  }

  /** Creates the isolated retry/DLQ policy that never pauses critical services. */
  @Bean
  NonCriticalDeliveryController marketDataProjectionDeliveryController(
      MarketDataProjectionProperties properties,
      Clock marketDataProjectionClock,
      DeadLetterStore marketDataProjectionDeadLetterStore,
      ObjectProvider<MeterRegistry> meterRegistryProvider) {
    final MeterRegistry meterRegistry = meterRegistryProvider.getIfAvailable();
    final DeliveryMetrics metrics =
        meterRegistry == null
            ? DeliveryMetrics.noop()
            : new MicrometerDeliveryMetrics(meterRegistry);
    return new NonCriticalDeliveryController(
        "market-data-projection",
        properties.matchingEvents().maximumAttempts(),
        properties.matchingEvents().retryDelay(),
        marketDataProjectionClock,
        marketDataProjectionDeadLetterStore,
        metrics);
  }

  /** Creates a daemon scheduler that retries only projection-owned work. */
  @Bean(destroyMethod = "close")
  MarketDataProjectionRetryScheduler marketDataProjectionRetryScheduler() {
    return new ScheduledMarketDataProjectionRetryScheduler();
  }

  /** Creates the independent final Matching Event consumer group boundary. */
  @Bean
  MarketDataProjectionConsumer marketDataProjectionConsumer(
      MarketDataProjectionHandler marketDataProjectionHandler,
      NonCriticalDeliveryController marketDataProjectionDeliveryController,
      MarketDataProjectionRetryScheduler marketDataProjectionRetryScheduler) {
    return new MarketDataProjectionConsumer(
        marketDataProjectionHandler,
        marketDataProjectionDeliveryController,
        marketDataProjectionRetryScheduler);
  }

  /** Stops the named non-critical listener before an operator resets projection state. */
  @Bean
  MarketDataProjectionConsumerControl marketDataProjectionConsumerControl(
      KafkaListenerEndpointRegistry listenerRegistry) {
    return () -> {
      final var container = listenerRegistry.getListenerContainer("market-data-projection");
      if (container == null) {
        throw new IllegalStateException("market-data-projection listener is not registered");
      }
      container.stop();
    };
  }
}
