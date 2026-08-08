package com.simplematch.accountservice.config;

import com.simplematch.accountservice.kafka.AccountLifecycleConsumer;
import com.simplematch.accountservice.reservation.AccountMatchingExecutionApplicationService;
import com.simplematch.accountservice.store.JdbcAccountQuarantineStore;
import com.simplematch.config.delivery.CriticalDeliveryController;
import com.simplematch.config.delivery.DeliveryMetrics;
import com.simplematch.config.delivery.MicrometerDeliveryMetrics;
import com.simplematch.config.delivery.QuarantineStore;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.EnableKafka;

/** Wires Account Authority's critical ordered lifecycle consumer. */
@Configuration(proxyBeanMethods = false)
@EnableKafka
@EnableConfigurationProperties(AccountLifecycleConsumerProperties.class)
public class AccountLifecycleConsumerConfiguration {
  /** Creates durable account quarantine evidence storage. */
  @Bean
  QuarantineStore accountQuarantineStore(JdbcTemplate jdbcTemplate) {
    return new JdbcAccountQuarantineStore(jdbcTemplate);
  }

  /** Creates the bounded same-offset retry policy for lifecycle events. */
  @Bean
  CriticalDeliveryController accountLifecycleDeliveryController(
      AccountLifecycleConsumerProperties properties,
      Clock accountServiceClock,
      QuarantineStore quarantineStore,
      ObjectProvider<MeterRegistry> meterRegistryProvider) {
    final MeterRegistry meterRegistry = meterRegistryProvider.getIfAvailable();
    final DeliveryMetrics metrics =
        meterRegistry == null
            ? DeliveryMetrics.noop()
            : new MicrometerDeliveryMetrics(meterRegistry);
    return new CriticalDeliveryController(
        "account-lifecycle",
        properties.maximumAttempts(),
        properties.recoveryInstructions(),
        accountServiceClock,
        quarantineStore,
        metrics);
  }

  /** Creates the public account lifecycle consumer boundary. */
  @Bean
  AccountLifecycleConsumer accountLifecycleConsumer(
      AccountMatchingExecutionApplicationService accountService,
      CriticalDeliveryController deliveryController) {
    return new AccountLifecycleConsumer(accountService, deliveryController);
  }
}
