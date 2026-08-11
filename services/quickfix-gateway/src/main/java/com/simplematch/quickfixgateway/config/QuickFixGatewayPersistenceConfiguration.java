package com.simplematch.quickfixgateway.config;

import com.simplematch.config.PostgresJdbcUrl;
import com.simplematch.config.PostgresProperties;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Configures the Gateway-owned PostgreSQL schema used by QuickFIX and final-event delivery. */
@Configuration(proxyBeanMethods = false)
public class QuickFixGatewayPersistenceConfiguration {
  private static final String QUICKFIX_GATEWAY_SCHEMA = "quickfix_gateway";

  /** Creates the Gateway-local JDBC pool from the platform PostgreSQL DSN. */
  @Bean
  DataSource quickFixGatewayDataSource(PostgresProperties properties) {
    final PostgresJdbcUrl jdbcUrl = PostgresJdbcUrl.parse(properties.dsn());
    final HikariDataSource dataSource = new HikariDataSource();
    dataSource.setJdbcUrl(jdbcUrl.jdbcUrl());
    if (jdbcUrl.username() != null) {
      dataSource.setUsername(jdbcUrl.username());
    }
    if (jdbcUrl.password() != null) {
      dataSource.setPassword(jdbcUrl.password());
    }
    dataSource.setSchema(QUICKFIX_GATEWAY_SCHEMA);
    dataSource.setMaximumPoolSize(4);
    dataSource.setPoolName("quickfix-gateway-hikari");
    return dataSource;
  }
}
