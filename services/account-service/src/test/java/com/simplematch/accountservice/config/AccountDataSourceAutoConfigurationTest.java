package com.simplematch.accountservice.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.simplematch.config.PostgresProperties;
import com.simplematch.config.SimpleMatchDataSourceAutoConfiguration;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

class AccountDataSourceAutoConfigurationTest {
  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  SimpleMatchDataSourceAutoConfiguration.class, DataSourceAutoConfiguration.class))
          .withUserConfiguration(AccountDataSourceConfiguration.class);

  @Test
  void mapsPostgresUriCredentialsAndIgnoresSpringDatasourceNamespace() {
    contextRunner
        .withPropertyValues(
            "simplematch.postgres.dsn=postgresql://alice:secret@db.example:5433/simplematch?sslmode=require",
            "spring.datasource.url=jdbc:h2:mem:wrong-source")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).hasSingleBean(DataSource.class);

              final HikariDataSource dataSource = context.getBean(HikariDataSource.class);
              assertThat(dataSource.getJdbcUrl())
                  .isEqualTo(
                      "jdbc:postgresql://db.example:5433/simplematch?sslmode=require");
              assertThat(dataSource.getUsername()).isEqualTo("alice");
              assertThat(dataSource.getPassword()).isEqualTo("secret");
              assertThat(dataSource.getSchema()).isEqualTo("account_service");
              assertThat(dataSource.getMaximumPoolSize()).isEqualTo(4);
              assertThat(dataSource.getPoolName()).isEqualTo("account-service-hikari");
            });
  }

  @Test
  void keepsH2TestDsnUsableWithAccountSchemaAndPoolPolicy() {
    contextRunner
        .withPropertyValues(
            "simplematch.postgres.dsn=jdbc:h2:mem:account_auto_config;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;INIT=CREATE SCHEMA IF NOT EXISTS account_service\\;SET SCHEMA account_service",
            "spring.datasource.url=jdbc:h2:mem:wrong-source")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              final HikariDataSource dataSource = context.getBean(HikariDataSource.class);

              assertThat(dataSource.getJdbcUrl()).startsWith("jdbc:h2:mem:account_auto_config");
              assertThat(dataSource.getMaximumPoolSize()).isEqualTo(4);
              assertThat(dataSource.getPoolName()).isEqualTo("account-service-hikari");
              assertThat(currentSchema(dataSource)).isEqualTo("ACCOUNT_SERVICE");
            });
  }

  @Test
  void rejectsUnsupportedDsnAtStartupWithoutLeakingCredentials() {
    contextRunner
        .withPropertyValues("simplematch.postgres.dsn=mysql://alice:secret@db.example/simplematch")
        .run(
            context -> {
              assertThat(context).hasFailed();
              final Throwable failure = context.getStartupFailure();
              assertThat(failure).isNotNull().hasMessageNotContaining("secret");
              assertThat(rootCause(failure))
                  .isInstanceOf(IllegalStateException.class)
                  .hasMessage("unsupported postgres.dsn scheme: mysql");
            });
  }

  @Test
  void rejectsMalformedDsnAtStartupWithoutLeakingCredentials() {
    contextRunner
        .withPropertyValues("simplematch.postgres.dsn=postgresql://alice:secret@/simplematch")
        .run(
            context -> {
              assertThat(context).hasFailed();
              final Throwable failure = context.getStartupFailure();
              assertThat(failure).isNotNull().hasMessageNotContaining("secret");
              assertThat(rootCause(failure))
                  .isInstanceOf(IllegalStateException.class)
                  .hasMessage("postgres.dsn must identify a host and database");
            });
  }

  @Test
  void rejectsBlankDsnAtStartupWithStableDiagnostic() {
    contextRunner
        .withPropertyValues("simplematch.postgres.dsn= ")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(rootCause(context.getStartupFailure()))
                  .isInstanceOf(IllegalStateException.class)
                  .hasMessage("postgres.dsn must not be blank");
            });
  }

  private static Throwable rootCause(Throwable failure) {
    Throwable current = failure;
    while (current.getCause() != null) {
      current = current.getCause();
    }
    return current;
  }

  private static String currentSchema(HikariDataSource dataSource) {
    try (Connection connection = dataSource.getConnection()) {
      return connection.getSchema();
    } catch (SQLException exception) {
      throw new AssertionError("H2 DataSource could not open a connection", exception);
    }
  }

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(PostgresProperties.class)
  @Import(AccountPersistenceConfiguration.class)
  static class AccountDataSourceConfiguration {}
}
