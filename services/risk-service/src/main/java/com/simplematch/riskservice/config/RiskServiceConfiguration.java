package com.simplematch.riskservice.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simplematch.config.PlatformProperties;
import com.simplematch.riskservice.admission.AccountReservationClient;
import com.simplematch.riskservice.admission.AdmissionBackpressurePolicy;
import com.simplematch.riskservice.admission.AdmissionOutboxFactory;
import com.simplematch.riskservice.admission.CdcLagBackpressurePolicy;
import com.simplematch.riskservice.admission.CdcLagReader;
import com.simplematch.riskservice.admission.GrpcAccountReservationClient;
import com.simplematch.riskservice.outbox.FileRoutingPartitionResolver;
import com.simplematch.riskservice.outbox.OutboxRepository;
import com.simplematch.riskservice.outbox.RoutingPartitionResolver;
import com.simplematch.riskservice.outbox.SubmissionOutboxFactory;
import com.simplematch.riskservice.store.JdbcOutboxRepository;
import com.simplematch.riskservice.store.JdbcSubmissionRepository;
import com.simplematch.riskservice.submission.SubmissionService;
import com.simplematch.riskservice.submission.SubmissionValidator;
import com.simplematch.riskservice.submission.TransactionalSubmissionService;
import com.simplematch.riskservice.submission.V1AdmissionCompatibilityAdapter;
import java.nio.file.Path;
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
  AccountReservationClient accountReservationClient(PlatformProperties properties) {
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
      PlatformProperties properties,
      Clock riskServiceClock,
      RoutingPartitionResolver routingPartitionResolver) {
    return new AdmissionOutboxFactory(
        properties.kafka().topics().ordersValidated(), riskServiceClock, routingPartitionResolver);
  }

  @Bean
  OutboxRepository admissionOutboxRepository(JdbcTemplate riskJdbcTemplate) {
    return new JdbcOutboxRepository(riskJdbcTemplate);
  }

  @Bean
  RoutingPartitionResolver routingPartitionResolver(
      ObjectMapper objectMapper, PlatformProperties properties) {
    return FileRoutingPartitionResolver.load(
        objectMapper,
        Path.of(properties.routing().snapshotPath()),
        properties.kafka().partitions().ordersValidated());
  }

  @Bean
  SubmissionService submissionService(
      JdbcTemplate riskJdbcTemplate,
      TransactionTemplate riskTransactionTemplate,
      Clock riskServiceClock,
      ObjectMapper objectMapper,
      RoutingPartitionResolver routingPartitionResolver,
      PlatformProperties properties) {
    final SubmissionService legacy =
        new TransactionalSubmissionService(
            new SubmissionValidator(riskServiceClock),
            new SubmissionOutboxFactory(
                objectMapper,
                properties.kafka().topics().ordersValidated(),
                routingPartitionResolver),
            new JdbcSubmissionRepository(riskJdbcTemplate),
            new JdbcOutboxRepository(riskJdbcTemplate),
            riskTransactionTemplate);
    return new V1AdmissionCompatibilityAdapter(legacy);
  }
}
