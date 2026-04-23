package com.simplematch.riskservice.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simplematch.config.SimpleMatchConfig;
import com.simplematch.riskservice.bootstrap.RiskServiceRuntime;
import com.simplematch.riskservice.store.JdbcOutboxRepository;
import com.simplematch.riskservice.store.JdbcSubmissionRepository;
import com.simplematch.riskservice.submission.SubmissionIdempotencyKeyFactory;
import com.simplematch.riskservice.submission.SubmissionOutboxFactory;
import com.simplematch.riskservice.submission.SubmissionService;
import com.simplematch.riskservice.submission.SubmissionValidator;
import com.simplematch.riskservice.submission.TransactionalSubmissionService;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
public class RiskServiceConfiguration {
  @Bean
  ObjectMapper riskObjectMapper() {
    return new ObjectMapper();
  }

  @Bean
  Clock riskServiceClock() {
    return Clock.systemUTC();
  }

  @Bean
  RiskServiceRuntime riskServiceRuntime(SimpleMatchConfig config) {
    return RiskServiceRuntime.from(config);
  }

  @Bean
  DataSource riskServiceDataSource(SimpleMatchConfig config) {
    final PostgresJdbcConfig parsedJdbcDsn = PostgresJdbcConfig.parse(config.getPostgres().getDsn());
    final HikariDataSource dataSource = new HikariDataSource();
    dataSource.setJdbcUrl(parsedJdbcDsn.jdbcUrl());
    if (parsedJdbcDsn.username() != null) {
      dataSource.setUsername(parsedJdbcDsn.username());
    }
    if (parsedJdbcDsn.password() != null) {
      dataSource.setPassword(parsedJdbcDsn.password());
    }
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
    return new TransactionTemplate(riskTransactionManager);
  }

  @Bean
  SubmissionService submissionService(
      JdbcTemplate riskJdbcTemplate,
      TransactionTemplate riskTransactionTemplate,
      Clock riskServiceClock,
      ObjectMapper objectMapper,
      SimpleMatchConfig config) {
    return new TransactionalSubmissionService(
        new SubmissionIdempotencyKeyFactory(),
        new SubmissionValidator(riskServiceClock),
        new SubmissionOutboxFactory(objectMapper, config.getKafka().getTopics().getOrdersValidated()),
        new JdbcSubmissionRepository(riskJdbcTemplate),
        new JdbcOutboxRepository(riskJdbcTemplate),
        riskTransactionTemplate);
  }
}