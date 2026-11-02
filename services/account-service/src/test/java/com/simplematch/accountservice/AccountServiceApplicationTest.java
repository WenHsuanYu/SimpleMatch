package com.simplematch.accountservice;

import static org.assertj.core.api.Assertions.assertThat;

import com.simplematch.accountservice.bootstrap.AccountServiceRuntime;
import com.simplematch.config.EnvironmentProperties;
import com.simplematch.config.GrpcProperties;
import com.simplematch.config.PostgresProperties;
import com.simplematch.config.SimpleMatchDataSourceSettings;
import com.simplematch.config.testsupport.PostgresTestDatabase;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:postgresql://configuration.invalid:5432/wrong-source",
      "simplematch.account-service.grpc.enabled=false",
      "spring.flyway.enabled=false",
      "spring.main.web-application-type=none"
    })
@ActiveProfiles("test")
class AccountServiceApplicationTest {
  private static final DriverManagerDataSource DATABASE = database();

  @DynamicPropertySource
  static void databaseProperties(DynamicPropertyRegistry registry) {
    registry.add("simplematch.postgres.dsn", () -> PostgresTestDatabase.dsn(DATABASE));
  }

  private static DriverManagerDataSource database() {
    final var source = PostgresTestDatabase.create("account-service");
    Flyway.configure().dataSource(source).schemas("account_service")
        .defaultSchema("account_service").locations("classpath:db/migration/account-service")
        .load().migrate();
    return source;
  }

  @Autowired private EnvironmentProperties environmentProperties;

  @Autowired private PostgresProperties postgresProperties;

  @Autowired private DataSource dataSource;

  @Autowired private JdbcTemplate jdbcTemplate;

  @Autowired private SimpleMatchDataSourceSettings dataSourceSettings;

  @Autowired private AccountServiceRuntime runtime;

  // Verify that account-service loads shared configuration and creates its runtime on startup.
  // Scenario: start the Spring context with gRPC disabled and check the default environment and
  // gRPC port.
  @DisplayName("account-service loads shared config and runtime on startup")
  @Test
  void contextLoadsWithSharedConfig() {
    assertThat(environmentProperties.environment()).isEqualTo("test");
    assertThat(runtime.grpcPort()).isEqualTo(50051);
    assertThat(postgresProperties.dsn()).isEqualTo(PostgresTestDatabase.dsn(DATABASE));
    assertThat(
            AccountServiceRuntime.from(
                    new GrpcProperties(
                        new GrpcProperties.GrpcTargetsProperties(
                            "dns:///account-service:51051", "dns:///risk-service:50052")))
                .grpcPort())
        .isEqualTo(51051);
  }

  @DisplayName("account-service uses the typed DSN and owns only its pool policy")
  @Test
  void dataSourceUsesTypedDsnInsteadOfSpringDatasourceNamespace() {
    assertThat(dataSourceSettings.schema()).isEqualTo("account_service");
    assertThat(dataSourceSettings.maximumPoolSize()).isEqualTo(4);
    assertThat(dataSourceSettings.poolName()).isEqualTo("account-service-hikari");

    final HikariDataSource hikariDataSource = (HikariDataSource) dataSource;
    assertThat(hikariDataSource.getJdbcUrl()).isEqualTo(DATABASE.getUrl());
    assertThat(hikariDataSource.getMaximumPoolSize()).isEqualTo(4);
    assertThat(hikariDataSource.getPoolName()).isEqualTo("account-service-hikari");
    assertThat(jdbcTemplate.queryForObject("SELECT CURRENT_SCHEMA()", String.class))
        .isEqualTo("account_service");
  }
}
