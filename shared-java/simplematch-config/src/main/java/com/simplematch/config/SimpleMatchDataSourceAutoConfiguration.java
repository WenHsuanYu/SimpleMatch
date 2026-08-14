package com.simplematch.config;

import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Adapts the canonical SimpleMatch PostgreSQL DSN to Spring Boot's managed JDBC pool lifecycle.
 *
 * <p>Services provide {@link SimpleMatchDataSourceSettings}; connection credentials and the URL
 * remain owned by {@link PostgresProperties}. No {@code spring.datasource.*} property is read.
 */
@AutoConfiguration(before = DataSourceAutoConfiguration.class)
@ConditionalOnClass({DataSource.class, HikariDataSource.class})
@ConditionalOnBean(SimpleMatchDataSourceSettings.class)
@ConditionalOnMissingBean(DataSource.class)
public final class SimpleMatchDataSourceAutoConfiguration {
  private SimpleMatchDataSourceAutoConfiguration() {}

  /** Creates the Hikari pool from the canonical DSN and applies the service's pool policy. */
  @Bean
  @ConditionalOnMissingBean(DataSource.class)
  DataSource simpleMatchDataSource(
      PostgresProperties postgresProperties,
      SimpleMatchDataSourceSettings settings) {
    final PostgresJdbcUrl parsedDsn = PostgresJdbcUrl.parse(postgresProperties.dsn());
    final DataSourceBuilder<HikariDataSource> dataSourceBuilder =
        DataSourceBuilder.create().type(HikariDataSource.class).url(parsedDsn.jdbcUrl());
    if (parsedDsn.username() != null) {
      dataSourceBuilder.username(parsedDsn.username());
    }
    if (parsedDsn.password() != null) {
      dataSourceBuilder.password(parsedDsn.password());
    }
    final HikariDataSource dataSource = dataSourceBuilder.build();
    // H2 executes URL INIT statements while opening its first connection, after Hikari applies
    // the configured schema. The test URL owns schema selection, so applying it twice fails.
    if (!parsedDsn.jdbcUrl().startsWith("jdbc:h2:")) {
      dataSource.setSchema(settings.schema());
    }
    dataSource.setMaximumPoolSize(settings.maximumPoolSize());
    dataSource.setPoolName(settings.poolName());
    return dataSource;
  }
}
