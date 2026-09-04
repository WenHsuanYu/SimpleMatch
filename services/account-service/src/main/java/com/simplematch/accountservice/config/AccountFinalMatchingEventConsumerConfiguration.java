package com.simplematch.accountservice.config;

import com.simplematch.accountservice.matching.AccountFinalMatchingEventStatus;
import com.simplematch.accountservice.matching.AccountFinalMatchingEventsHealthIndicator;
import com.simplematch.accountservice.matching.FinalMatchingEventAccountConsumer;
import com.simplematch.accountservice.matching.FinalMatchingEventAccountHandler;
import com.simplematch.accountservice.store.JdbcAccountFinalMatchingEventQuarantineStore;
import com.simplematch.accountservice.store.JdbcFinalMatchingEventAccountInbox;
import com.simplematch.config.delivery.CriticalDeliveryController;
import com.simplematch.config.delivery.DeliveryMetrics;
import com.simplematch.config.delivery.DeliveryPosition;
import com.simplematch.config.delivery.MicrometerDeliveryMetrics;
import com.simplematch.config.delivery.QuarantineStore;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** Wires Account's raw-hash final Matching Event consumer. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AccountFinalMatchingEventConsumerProperties.class)
public class AccountFinalMatchingEventConsumerConfiguration {
  private static final String CONSUMER_NAME = "account-final-matching-events";

  /** Creates durable raw-byte quarantine storage for Account's final Matching Event consumer. */
  @Bean("accountFinalMatchingEventQuarantineStore")
  QuarantineStore accountFinalMatchingEventQuarantineStore(JdbcTemplate jdbcTemplate) {
    return new JdbcAccountFinalMatchingEventQuarantineStore(jdbcTemplate);
  }

  /** Creates the in-place retry and quarantine controller for final Matching Events. */
  @Bean("accountFinalMatchingEventDeliveryController")
  CriticalDeliveryController accountFinalMatchingEventDeliveryController(
      AccountFinalMatchingEventConsumerProperties properties,
      Clock accountServiceClock,
      @Qualifier("accountFinalMatchingEventQuarantineStore") QuarantineStore quarantineStore,
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
        accountServiceClock,
        quarantineStore,
        metrics);
  }

  /** Creates Account readiness state from durable progress and unresolved quarantine state. */
  @Bean
  @DependsOnDatabaseInitialization
  AccountFinalMatchingEventStatus accountFinalMatchingEventStatus(
      JdbcFinalMatchingEventAccountInbox inbox,
      Clock accountServiceClock,
      AccountFinalMatchingEventConsumerProperties properties,
      @Qualifier("accountFinalMatchingEventQuarantineStore") QuarantineStore quarantineStore,
      @Qualifier("accountFinalMatchingEventDeliveryController")
          CriticalDeliveryController deliveryController) {
    final AccountFinalMatchingEventStatus status =
        new AccountFinalMatchingEventStatus(accountServiceClock);
    if (properties.enabled()) {
      inbox.loadLastProcessedOffsets().forEach(status::recordCommitted);
      final List<DeliveryPosition> openPositions =
          quarantineStore.loadOpenPositions(CONSUMER_NAME);
      deliveryController.restoreQuarantines(openPositions);
      openPositions.stream().findFirst().ifPresent(status::recordQuarantined);
    }
    return status;
  }

  /** Creates Account's public critical final-event consumer boundary. */
  @Bean
  FinalMatchingEventAccountConsumer finalMatchingEventAccountConsumer(
      FinalMatchingEventAccountHandler handler,
      @Qualifier("accountFinalMatchingEventDeliveryController")
          CriticalDeliveryController deliveryController,
      AccountFinalMatchingEventStatus status) {
    return new FinalMatchingEventAccountConsumer(handler, deliveryController, status);
  }

  /** Exposes final Matching Event critical-consumer readiness via actuator health. */
  @Bean("accountFinalMatchingEventsReadyHealthIndicator")
  AccountFinalMatchingEventsHealthIndicator accountFinalMatchingEventsReadyHealthIndicator(
      AccountFinalMatchingEventStatus status) {
    return new AccountFinalMatchingEventsHealthIndicator(status);
  }
}
