package com.simplematch.queryservice;

import static org.assertj.core.api.Assertions.assertThat;

import com.simplematch.config.SimpleMatchDataSourceSettings;
import com.simplematch.queryservice.runtime.NoopQueryReadCache;
import com.simplematch.queryservice.runtime.QueryProjectionConsumerControl;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(
    properties = {
      // Intentionally wrong: this test verifies that the canonical SimpleMatch DSN remains
      // authoritative instead of Spring Boot's generic datasource namespace.
      "spring.datasource.url=jdbc:h2:mem:wrong-query-source"
    })
@ActiveProfiles("test")
class QueryServiceApplicationTest {
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
    assertThat(hikariDataSource.getJdbcUrl())
        .isEqualTo("jdbc:h2:mem:query-context;MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
    assertThat(hikariDataSource.getMaximumPoolSize()).isEqualTo(4);
    assertThat(hikariDataSource.getPoolName()).isEqualTo("query-service-hikari");

    // Verify the effective database session rather than Hikari's schema configuration property.
    // H2 uses connectionInitSql here, so HikariDataSource#getSchema() is intentionally not the
    // contract under test.
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
