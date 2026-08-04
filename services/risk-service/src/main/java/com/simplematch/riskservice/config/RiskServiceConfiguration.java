package com.simplematch.riskservice.config;

import com.simplematch.config.GrpcProperties;
import com.simplematch.config.KafkaProperties;
import com.simplematch.riskservice.admission.AccountReservationClient;
import com.simplematch.riskservice.admission.AdmissionBackpressurePolicy;
import com.simplematch.riskservice.admission.AdmissionJournalRepository;
import com.simplematch.riskservice.admission.AdmissionLifecycleTransactions;
import com.simplematch.riskservice.admission.AdmissionOutboxFactory;
import com.simplematch.riskservice.admission.AdmissionRoutingPolicyResolver;
import com.simplematch.riskservice.admission.CdcLagBackpressurePolicy;
import com.simplematch.riskservice.admission.CdcLagReader;
import com.simplematch.riskservice.admission.GrpcAccountReservationClient;
import com.simplematch.riskservice.outbox.OutboxRepository;
import com.simplematch.riskservice.store.JdbcOutboxRepository;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** Composes Risk Admission and submission collaborators over shared local infrastructure. */
@Configuration
@EnableConfigurationProperties(RiskServiceProperties.class)
@Import(RiskServicePersistenceConfiguration.class)
public class RiskServiceConfiguration {
  @Bean
  AccountReservationClient accountReservationClient(GrpcProperties properties) {
    return new GrpcAccountReservationClient(properties);
  }

  @Bean
  AdmissionBackpressurePolicy admissionBackpressurePolicy(
      CdcLagReader cdcLagReader, Clock riskServiceClock, RiskServiceProperties properties) {
    final RiskServiceProperties.AdmissionProperties admission = properties.admission();

    return new CdcLagBackpressurePolicy(
        cdcLagReader,
        admission.cdcMetricName(),
        admission.maximumCdcLagEvents(),
        admission.maximumMetricAge(),
        riskServiceClock);
  }

  @Bean
  AdmissionOutboxFactory admissionOutboxFactory(
      KafkaProperties properties,
      Clock riskServiceClock) {
    return new AdmissionOutboxFactory(properties.topics().ordersValidated(), riskServiceClock);
  }

  @Bean
  OutboxRepository admissionOutboxRepository(JdbcTemplate riskJdbcTemplate) {
    return new JdbcOutboxRepository(riskJdbcTemplate);
  }

  @Bean
  AdmissionLifecycleTransactions admissionLifecycleTransactions(
      AdmissionJournalRepository journal,
      OutboxRepository outbox,
      AdmissionOutboxFactory events,
      AdmissionRoutingPolicyResolver routingPolicyResolver,
      Clock riskServiceClock,
      TransactionTemplate riskTransactionTemplate) {
    return new AdmissionLifecycleTransactions(
        journal,
        outbox,
        events,
        routingPolicyResolver,
        riskServiceClock,
        riskTransactionTemplate);
  }

}
