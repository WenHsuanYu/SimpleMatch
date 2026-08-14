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
    if (parsedDsn.jdbcUrl().startsWith("jdbc:h2:")) {
      dataSource.setConnectionInitSql(h2SchemaInitialization(settings.schema()));
    } else {
      dataSource.setSchema(settings.schema());
    }
    dataSource.setMaximumPoolSize(settings.maximumPoolSize());
    dataSource.setPoolName(settings.poolName());
    return dataSource;
  }

  private static String h2SchemaInitialization(String schema) {
    final String quotedSchema = "\"" + schema.replace("\"", "\"\"") + "\"";
    return "CREATE SCHEMA IF NOT EXISTS " + quotedSchema + "; SET SCHEMA " + quotedSchema;
  }
}
