package com.simplematch.riskservice.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simplematch.config.GrpcProperties;
import com.simplematch.config.PostgresJdbcUrl;
import com.simplematch.config.PostgresProperties;
import com.simplematch.riskservice.admission.CdcLagReader;
import com.simplematch.riskservice.bootstrap.RiskServiceRuntime;
import com.simplematch.riskservice.store.JdbcCdcLagReader;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Configures Risk service runtime, database, and persistence infrastructure. */
@Configuration(proxyBeanMethods = false)
class RiskServicePersistenceConfiguration {
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
  RiskServiceRuntime riskServiceRuntime(GrpcProperties properties) {
    return RiskServiceRuntime.from(properties);
  }

  @Bean
  DataSource riskServiceDataSource(PostgresProperties properties) {
    final PostgresJdbcUrl parsedJdbcDsn = PostgresJdbcUrl.parse(properties.dsn());
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
  CdcLagReader cdcLagReader(JdbcTemplate riskJdbcTemplate) {
    return new JdbcCdcLagReader(riskJdbcTemplate);
  }
}
