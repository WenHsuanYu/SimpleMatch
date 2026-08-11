package com.simplematch.queryservice;

import static org.assertj.core.api.Assertions.assertThat;

import com.simplematch.config.SimpleMatchDataSourceSettings;
import com.simplematch.queryservice.runtime.NoopQueryReadCache;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(
    properties = {
      "simplematch.postgres.dsn=jdbc:h2:mem:query-context;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;INIT=CREATE SCHEMA IF NOT EXISTS query_service\\;SET SCHEMA query_service",
      "spring.datasource.url=jdbc:h2:mem:wrong-query-source",
      "simplematch.query-service.matching-events.enabled=false",
      "simplematch.query-service.account-lifecycle.enabled=false",
      "simplematch.query-service.redis.enabled=false",
      "spring.flyway.enabled=false",
      "spring.main.web-application-type=none"
    })
@ActiveProfiles("test")
class QueryServiceApplicationTest {
  @Autowired private DataSource dataSource;

  @Autowired private SimpleMatchDataSourceSettings dataSourceSettings;

  @Autowired private NoopQueryReadCache queryReadCache;

  @DisplayName("query-service uses the canonical DSN and shared pool policy")
  @Test
  void usesTypedDataSourceAdapterAndLocalCacheFallback() {
    assertThat(dataSourceSettings.schema()).isEqualTo("query_service");
    assertThat(dataSourceSettings.maximumPoolSize()).isEqualTo(4);
    assertThat(dataSourceSettings.poolName()).isEqualTo("query-service-hikari");
    assertThat(queryReadCache).isNotNull();

    final HikariDataSource hikariDataSource = (HikariDataSource) dataSource;
    assertThat(hikariDataSource.getJdbcUrl()).startsWith("jdbc:h2:mem:query-context");
    assertThat(hikariDataSource.getSchema()).isEqualTo("query_service");
    assertThat(hikariDataSource.getMaximumPoolSize()).isEqualTo(4);
    assertThat(hikariDataSource.getPoolName()).isEqualTo("query-service-hikari");
  }
}
