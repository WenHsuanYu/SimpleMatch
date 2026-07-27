package com.simplematch.riskservice.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simplematch.config.PlatformProperties;
import com.simplematch.riskservice.bootstrap.RiskServiceRuntime;
import com.simplematch.riskservice.admission.AccountReservationClient;
import com.simplematch.riskservice.admission.AdmissionBackpressurePolicy;
import com.simplematch.riskservice.admission.AdmissionOutboxFactory;
import com.simplematch.riskservice.admission.CdcLagBackpressurePolicy;
import com.simplematch.riskservice.admission.GrpcAccountReservationClient;
import com.simplematch.riskservice.outbox.FileRoutingPartitionResolver;
import com.simplematch.riskservice.outbox.RoutingPartitionResolver;
import com.simplematch.riskservice.outbox.SubmissionOutboxFactory;
import com.simplematch.riskservice.outbox.OutboxRepository;
import com.simplematch.riskservice.store.JdbcOutboxRepository;
import com.simplematch.riskservice.store.JdbcSubmissionRepository;
import com.simplematch.riskservice.submission.SubmissionService;
import com.simplematch.riskservice.submission.SubmissionValidator;
import com.simplematch.riskservice.submission.TransactionalSubmissionService;
import com.simplematch.riskservice.submission.V1AdmissionCompatibilityAdapter;
import java.nio.file.Path;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Clock;
import java.util.function.LongSupplier;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
@EnableScheduling
public class RiskServiceConfiguration {
  private static final String RISK_SERVICE_SCHEMA = "risk_service";

  @Bean
  ObjectMapper riskObjectMapper() {
    return new ObjectMapper();
  }

  @Bean
  Clock riskServiceClock() {
    return Clock.systemUTC();
  }

  @Bean
  RiskServiceRuntime riskServiceRuntime(PlatformProperties properties) {
    return RiskServiceRuntime.from(properties);
  }

  @Bean
  DataSource riskServiceDataSource(PlatformProperties properties) {
    final PostgresJdbcConfig parsedJdbcDsn = PostgresJdbcConfig.parse(properties.postgres().dsn());
    final HikariDataSource dataSource = new HikariDataSource();
    dataSource.setJdbcUrl(parsedJdbcDsn.jdbcUrl());
    if (parsedJdbcDsn.username() != null) {
      dataSource.setUsername(parsedJdbcDsn.username());
    }
    if (parsedJdbcDsn.password() != null) {
      dataSource.setPassword(parsedJdbcDsn.password());
    }
    dataSource.setSchema(RISK_SERVICE_SCHEMA);
    dataSource.setMaximumPoolSize(4);
    dataSource.setPoolName("risk-service-hikari");
    return dataSource;
  }

  @Bean
  JdbcTemplate riskJdbcTemplate(DataSource riskServiceDataSource) {
    return new JdbcTemplate(riskServiceDataSource);
  }

  @Bean
  PlatformTransactionManager riskTransactionManager(DataSource riskServiceDataSource) {
    return new DataSourceTransactionManager(riskServiceDataSource);
  }

  @Bean
  TransactionTemplate riskTransactionTemplate(PlatformTransactionManager riskTransactionManager) {
    final TransactionTemplate template = new TransactionTemplate(riskTransactionManager);
    template.setTimeout(8);
    return template;
  }

  @Bean
  AccountReservationClient accountReservationClient(PlatformProperties properties) {
    return new GrpcAccountReservationClient(properties);
  }

  @Bean
  AdmissionBackpressurePolicy admissionBackpressurePolicy(JdbcTemplate riskJdbcTemplate) {
    final LongSupplier durableBacklog = () -> riskJdbcTemplate.queryForObject(
        "SELECT lag_events FROM risk_service.cdc_delivery_lag WHERE metric_name = 'orders.validated'", Long.class);
    return new CdcLagBackpressurePolicy(durableBacklog, 10_000L);
  }

  @Bean
  AdmissionOutboxFactory admissionOutboxFactory(
      PlatformProperties properties, Clock riskServiceClock) {
    return new AdmissionOutboxFactory(properties.kafka().topics().ordersValidated(), riskServiceClock);
  }

  @Bean
  OutboxRepository admissionOutboxRepository(JdbcTemplate riskJdbcTemplate) {
    return new JdbcOutboxRepository(riskJdbcTemplate);
  }

  @Bean
  RoutingPartitionResolver routingPartitionResolver(
      ObjectMapper objectMapper,
      PlatformProperties properties) {
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
    final SubmissionService legacy = new TransactionalSubmissionService(
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
