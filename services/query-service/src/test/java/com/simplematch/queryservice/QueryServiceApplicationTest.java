package com.simplematch.queryservice;

import static org.assertj.core.api.Assertions.assertThat;

import com.simplematch.config.SimpleMatchDataSourceSettings;
import com.simplematch.queryservice.runtime.NoopQueryReadCache;
import com.simplematch.queryservice.runtime.QueryProjectionConsumerControl;
import com.simplematch.queryservice.store.QueryProjectionTestDatabase;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(
    properties = {
      // Intentionally wrong: this test verifies that the canonical SimpleMatch DSN remains
      // authoritative instead of Spring Boot's generic datasource namespace.
      "spring.datasource.url=jdbc:postgresql://127.0.0.1:1/wrong-query-source"
    })
@ActiveProfiles("test")
class QueryServiceApplicationTest {
  @DynamicPropertySource
  static void canonicalPostgres(DynamicPropertyRegistry registry) {
    registry.add("simplematch.postgres.dsn", QueryProjectionTestDatabase::canonicalDsn);
  }

  @Autowired private DataSource dataSource;

  @Autowired private JdbcTemplate jdbcTemplate;

  @Autowired private SimpleMatchDataSourceSettings dataSourceSettings;

  @Autowired private NoopQueryReadCache queryReadCache;

  @Autowired private QueryProjectionConsumerControl queryProjectionConsumerControl;

  @Autowired private KafkaListenerEndpointRegistry listenerRegistry;

  @DisplayName("query-service uses the canonical DSN and shared pool policy")
  @Test
  void usesTypedDataSourceAdapterAndLocalCacheFallback() {
    assertThat(dataSourceSettings.schema()).isEqualTo("query_service");
    assertThat(dataSourceSettings.maximumPoolSize()).isEqualTo(4);
    assertThat(dataSourceSettings.poolName()).isEqualTo("query-service-hikari");
    assertThat(queryReadCache).isNotNull();

    final HikariDataSource hikariDataSource = (HikariDataSource) dataSource;
    assertThat(hikariDataSource.getJdbcUrl()).startsWith("jdbc:postgresql://");
    assertThat(hikariDataSource.getMaximumPoolSize()).isEqualTo(4);
    assertThat(hikariDataSource.getPoolName()).isEqualTo("query-service-hikari");

    assertThat(jdbcTemplate.queryForObject("SELECT current_database()", String.class))
        .startsWith("simplematch_query_jdbc_");
    assertThat(jdbcTemplate.queryForObject("SELECT CURRENT_SCHEMA()", String.class))
        .isEqualTo("query_service");
  }

  @DisplayName("query rebuild control owns both registered listeners")
  @Test
  void ownsBothProjectionListeners() {
    assertThat(listenerRegistry.getListenerContainer("query-service-matching-events")).isNotNull();
    assertThat(listenerRegistry.getListenerContainer("query-service-account-lifecycle")).isNotNull();

    queryProjectionConsumerControl.stop();

    assertThat(listenerRegistry.getListenerContainer("query-service-matching-events").isRunning())
        .isFalse();
    assertThat(listenerRegistry.getListenerContainer("query-service-account-lifecycle").isRunning())
        .isFalse();
  }
}
