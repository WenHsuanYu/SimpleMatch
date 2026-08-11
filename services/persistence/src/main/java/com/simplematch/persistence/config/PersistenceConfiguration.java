package com.simplematch.persistence.config;

import com.simplematch.config.PostgresJdbcUrl;
import com.simplematch.config.PostgresProperties;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Configures Persistence's isolated PostgreSQL owner schema and clock. */
@Configuration(proxyBeanMethods = false)
public class PersistenceConfiguration {
  private static final String PERSISTENCE_SCHEMA = "persistence";

  /** Supplies one UTC clock to transaction and progress writers. */
  @Bean
  Clock persistenceClock() {
    return Clock.systemUTC();
  }

  /** Creates the service-local pool from the platform PostgreSQL DSN. */
  @Bean
  DataSource persistenceDataSource(PostgresProperties properties) {
    final PostgresJdbcUrl jdbcUrl = PostgresJdbcUrl.parse(properties.dsn());
    final HikariDataSource dataSource = new HikariDataSource();
    dataSource.setJdbcUrl(jdbcUrl.jdbcUrl());
    if (jdbcUrl.username() != null) {
      dataSource.setUsername(jdbcUrl.username());
    }
    if (jdbcUrl.password() != null) {
      dataSource.setPassword(jdbcUrl.password());
    }
    dataSource.setSchema(PERSISTENCE_SCHEMA);
    dataSource.setMaximumPoolSize(4);
    dataSource.setPoolName("persistence-hikari");
    return dataSource;
  }
}
