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
import com.simplematch.persistence.store.JdbcMatchingEventStore;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.EnableKafka;

/** Wires Persistence's critical final-Matching-event delivery boundary. */
@Configuration(proxyBeanMethods = false)
@EnableKafka
@EnableConfigurationProperties(PersistenceMatchingEventConsumerProperties.class)
public class PersistenceMatchingEventConsumerConfiguration {
  private static final String CONSUMER_NAME = "persistence-matching-events";

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
        CONSUMER_NAME,
        properties.maximumAttempts(),
        properties.recoveryInstructions(),
        persistenceClock,
        persistenceMatchingEventQuarantineStore,
        metrics);
  }

  /** Creates Persistence readiness state from durable progress and unresolved quarantine state. */
  @Bean
  @DependsOnDatabaseInitialization
  PersistenceMatchingEventStatus persistenceMatchingEventStatus(
      JdbcMatchingEventStore store,
      Clock persistenceClock,
      PersistenceMatchingEventConsumerProperties properties,
      QuarantineStore persistenceMatchingEventQuarantineStore,
      CriticalDeliveryController persistenceMatchingEventDeliveryController) {
    final PersistenceMatchingEventStatus status =
        new PersistenceMatchingEventStatus(persistenceClock);
    if (properties.enabled()) {
      store.loadLastProcessedOffsets().forEach(status::recordCommitted);
      final List<com.simplematch.config.delivery.DeliveryPosition> openPositions =
          persistenceMatchingEventQuarantineStore.loadOpenPositions(CONSUMER_NAME);
      persistenceMatchingEventDeliveryController.restoreQuarantines(openPositions);
      openPositions.stream().findFirst().ifPresent(status::recordQuarantined);
    }
    return status;
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
