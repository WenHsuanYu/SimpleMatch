package com.simplematch.persistence.config;

import com.simplematch.config.delivery.CriticalDeliveryController;
import com.simplematch.config.delivery.DeliveryMetrics;
import com.simplematch.config.delivery.MicrometerDeliveryMetrics;
import com.simplematch.config.delivery.QuarantineStore;
import com.simplematch.persistence.kafka.JdbcPersistenceQuarantineStore;
import com.simplematch.persistence.kafka.PersistenceMatchingEventConsumer;
import com.simplematch.persistence.kafka.PersistenceMatchingEventConsumerProperties;
import com.simplematch.persistence.kafka.PersistenceMatchingEventStatus;
import com.simplematch.persistence.kafka.PersistenceMatchingEventsHealthIndicator;
import com.simplematch.persistence.matching.MatchingEventPersistenceHandler;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.EnableKafka;

/** Wires Persistence's critical final-Matching-event delivery boundary. */
@Configuration(proxyBeanMethods = false)
@EnableKafka
@EnableConfigurationProperties(PersistenceMatchingEventConsumerProperties.class)
public class PersistenceMatchingEventConsumerConfiguration {
  /** Creates durable raw-byte quarantine storage for Persistence's critical consumer. */
  @Bean
  QuarantineStore persistenceMatchingEventQuarantineStore(JdbcTemplate jdbcTemplate) {
    return new JdbcPersistenceQuarantineStore(jdbcTemplate);
  }

  /** Creates the strict same-offset retry and quarantine policy. */
  @Bean
  CriticalDeliveryController persistenceMatchingEventDeliveryController(
      PersistenceMatchingEventConsumerProperties properties,
      Clock persistenceClock,
      QuarantineStore persistenceMatchingEventQuarantineStore,
      ObjectProvider<MeterRegistry> meterRegistryProvider) {
    final MeterRegistry meterRegistry = meterRegistryProvider.getIfAvailable();
    final DeliveryMetrics metrics =
        meterRegistry == null
            ? DeliveryMetrics.noop()
            : new MicrometerDeliveryMetrics(meterRegistry);
    return new CriticalDeliveryController(
        "persistence-matching-events",
        properties.maximumAttempts(),
        properties.recoveryInstructions(),
        persistenceClock,
        persistenceMatchingEventQuarantineStore,
        metrics);
  }

  /** Creates the compact state projection used by Persistence readiness. */
  @Bean
  PersistenceMatchingEventStatus persistenceMatchingEventStatus() {
    return new PersistenceMatchingEventStatus();
  }

  /** Creates the public final-event consumer boundary. */
  @Bean
  PersistenceMatchingEventConsumer persistenceMatchingEventConsumer(
      MatchingEventPersistenceHandler persistenceHandler,
      CriticalDeliveryController persistenceMatchingEventDeliveryController,
      PersistenceMatchingEventStatus persistenceMatchingEventStatus) {
    return new PersistenceMatchingEventConsumer(
        persistenceHandler,
        persistenceMatchingEventDeliveryController,
        persistenceMatchingEventStatus);
  }

  /** Exposes final-event consumer readiness to the standard actuator health endpoint. */
  @Bean("persistenceMatchingEventsReadyHealthIndicator")
  PersistenceMatchingEventsHealthIndicator persistenceMatchingEventsReadyHealthIndicator(
      PersistenceMatchingEventStatus status) {
    return new PersistenceMatchingEventsHealthIndicator(status);
  }
}
