package com.simplematch.accountservice;

import static org.assertj.core.api.Assertions.assertThat;

import com.simplematch.accountservice.bootstrap.AccountServiceRuntime;
import com.simplematch.config.EnvironmentProperties;
import com.simplematch.config.GrpcProperties;
import com.simplematch.config.PostgresProperties;
import com.simplematch.config.SimpleMatchDataSourceSettings;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(
    properties = {
      "simplematch.postgres.dsn=jdbc:h2:mem:account_context;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;INIT=CREATE SCHEMA IF NOT EXISTS account_service\\\\;SET SCHEMA account_service",
      "spring.datasource.url=jdbc:h2:mem:wrong-source",
      "simplematch.account-service.grpc.enabled=false",
      "spring.flyway.enabled=false",
      "spring.main.web-application-type=none"
    })
@ActiveProfiles("test")
class AccountServiceApplicationTest {
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
    assertThat(postgresProperties.dsn())
        .isEqualTo(
            "jdbc:h2:mem:account_context;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;INIT=CREATE SCHEMA IF NOT EXISTS account_service\\;SET SCHEMA account_service");
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
    assertThat(hikariDataSource.getJdbcUrl())
        .isEqualTo(
            "jdbc:h2:mem:account_context;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;INIT=CREATE SCHEMA IF NOT EXISTS account_service\\;SET SCHEMA account_service");
    assertThat(hikariDataSource.getMaximumPoolSize()).isEqualTo(4);
    assertThat(hikariDataSource.getPoolName()).isEqualTo("account-service-hikari");
    assertThat(jdbcTemplate.queryForObject("SELECT CURRENT_SCHEMA()", String.class))
        .isEqualTo("account_service");
  }
}
