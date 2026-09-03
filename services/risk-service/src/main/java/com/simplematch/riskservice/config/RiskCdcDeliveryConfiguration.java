package com.simplematch.riskservice.config;

import com.simplematch.config.KafkaProperties;
import com.simplematch.config.delivery.DeliveryMetrics;
import com.simplematch.config.delivery.MicrometerDeliveryMetrics;
import com.simplematch.riskservice.cdc.CdcDeliveryMonitor;
import com.simplematch.riskservice.cdc.CdcDeliveryProgressProbe;
import com.simplematch.riskservice.cdc.CdcDeliveryProgressStore;
import com.simplematch.riskservice.cdc.KafkaCdcDeliveryListener;
import com.simplematch.riskservice.cdc.KafkaCdcDeliveryProgressProbe;
import com.simplematch.riskservice.store.JdbcCdcDeliveryProgressStore;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.HashMap;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.transaction.support.TransactionTemplate;

/** Wires the Risk-owned Kafka delivery observer and durable lag producer. */
@Configuration(proxyBeanMethods = false)
@EnableKafka
@EnableConfigurationProperties(CdcDeliveryProperties.class)
@ConditionalOnProperty(
    name = "simplematch.risk-service.cdc-delivery.enabled",
    havingValue = "true")
class RiskCdcDeliveryConfiguration {
  @Bean(destroyMethod = "close")
  Admin riskCdcDeliveryAdmin(
      org.springframework.boot.kafka.autoconfigure.KafkaProperties bootKafka,
      CdcDeliveryProperties properties) {
    final var adminProperties = new HashMap<>(bootKafka.buildAdminProperties());
    adminProperties.put(
        AdminClientConfig.CLIENT_ID_CONFIG,
        properties.consumerGroup() + "-progress");
    return Admin.create(adminProperties);
  }

  @Bean
  CdcDeliveryProgressStore cdcDeliveryProgressStore(JdbcTemplate riskJdbcTemplate) {
    return new JdbcCdcDeliveryProgressStore(riskJdbcTemplate);
  }

  @Bean
  CdcDeliveryProgressProbe cdcDeliveryProgressProbe(
      Admin riskCdcDeliveryAdmin, CdcDeliveryProperties properties) {
    return new KafkaCdcDeliveryProgressProbe(
        riskCdcDeliveryAdmin, properties.consumerGroup(), properties.queryTimeout());
  }

  @Bean
  KafkaCdcDeliveryListener kafkaCdcDeliveryListener(
      CdcDeliveryProgressStore store,
      Clock riskServiceClock,
      CdcDeliveryProperties properties) {
    return new KafkaCdcDeliveryListener(
        store, riskServiceClock, properties.fixtureRecordsAllowed());
  }

  @Bean
  CdcDeliveryMonitor cdcDeliveryMonitor(
      CdcDeliveryProgressStore store,
      CdcDeliveryProgressProbe probe,
      ObjectProvider<MeterRegistry> registryProvider,
      RiskServiceProperties risk,
      KafkaProperties kafka,
      Clock riskServiceClock,
      TransactionTemplate riskTransactionTemplate) {
    final MeterRegistry registry = registryProvider.getIfAvailable();
    final DeliveryMetrics metrics =
        registry == null ? DeliveryMetrics.noop() : new MicrometerDeliveryMetrics(registry);
    return new CdcDeliveryMonitor(
        store,
        probe,
        metrics,
        risk.admission().cdcMetricName(),
        kafka.topics().matchingCommands(),
        riskServiceClock,
        riskTransactionTemplate);
  }
}
