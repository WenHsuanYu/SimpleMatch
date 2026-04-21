package com.simplematch.riskservice.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simplematch.config.SimpleMatchConfig;
import com.simplematch.riskservice.bootstrap.RiskServiceRuntime;
import com.simplematch.riskservice.store.PostgresSubmissionStore;
import com.simplematch.riskservice.store.SubmissionStore;
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
  SubmissionStore submissionStore(
      JdbcTemplate riskJdbcTemplate,
      TransactionTemplate riskTransactionTemplate,
      ObjectMapper objectMapper,
      SimpleMatchConfig config) {
    return new PostgresSubmissionStore(
        riskJdbcTemplate,
        riskTransactionTemplate,
        objectMapper,
        config.getKafka().getTopics().getOrdersValidated());
  }
}