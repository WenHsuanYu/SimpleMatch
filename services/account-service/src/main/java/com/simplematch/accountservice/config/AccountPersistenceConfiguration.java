package com.simplematch.accountservice.config;

import com.simplematch.config.SimpleMatchConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AccountPersistenceConfiguration {
  private static final String ACCOUNT_SERVICE_SCHEMA = "account_service";

  @Bean
  Clock accountServiceClock() {
    return Clock.systemUTC();
  }

  @Bean
  DataSource accountServiceDataSource(SimpleMatchConfig config) {
    final PostgresJdbcConfig parsedJdbcDsn = PostgresJdbcConfig.parse(config.getPostgres().getDsn());
    final HikariDataSource dataSource = new HikariDataSource();
    dataSource.setJdbcUrl(parsedJdbcDsn.jdbcUrl());
    if (parsedJdbcDsn.username() != null) {
      dataSource.setUsername(parsedJdbcDsn.username());
    }
    if (parsedJdbcDsn.password() != null) {
      dataSource.setPassword(parsedJdbcDsn.password());
    }
    dataSource.setSchema(ACCOUNT_SERVICE_SCHEMA);
    dataSource.setMaximumPoolSize(4);
    dataSource.setPoolName("account-service-hikari");
    return dataSource;
  }
}