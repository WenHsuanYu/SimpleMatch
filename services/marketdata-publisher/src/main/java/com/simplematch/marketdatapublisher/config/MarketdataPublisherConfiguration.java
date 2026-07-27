package com.simplematch.marketdatapublisher.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simplematch.config.PlatformProperties;
import com.simplematch.config.SimpleMatchUuids;
import com.simplematch.marketdatapublisher.health.MarketSnapshotReadinessHealthIndicator;
import com.simplematch.marketdatapublisher.publication.JdbcMarketSnapshotRepository;
import com.simplematch.marketdatapublisher.publication.JdbcSnapshotOutbox;
import com.simplematch.marketdatapublisher.publication.MarketSnapshotApplicationService;
import com.simplematch.marketdatapublisher.publication.MarketSnapshotRepository;
import com.simplematch.marketdatapublisher.publication.SnapshotOutbox;
import com.simplematch.marketdatapublisher.snapshot.MarketSnapshotImportService;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Clock;
import java.util.UUID;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

/** Wires only local database publication dependencies; market sources remain offline adapters. */
@Configuration
public class MarketdataPublisherConfiguration {
  private static final String SCHEMA_NAME = "marketdata_publisher";

  /** Creates the JSON mapper used before the publication transaction and for event envelopes. */
  @Bean
  ObjectMapper marketdataPublisherObjectMapper() {
    return new ObjectMapper();
  }

  /** Provides UTC timestamps for durable metadata. */
  @Bean
  Clock marketdataPublisherClock() {
    return Clock.systemUTC();
  }

  /** Creates the service-owned PostgreSQL datasource. */
  @Bean
  DataSource dataSource(PlatformProperties properties) {
    final PostgresJdbcConfig parsed = PostgresJdbcConfig.parse(properties.postgres().dsn());
    final HikariDataSource dataSource = new HikariDataSource();
    dataSource.setJdbcUrl(parsed.jdbcUrl());
    if (parsed.username() != null) {
      dataSource.setUsername(parsed.username());
    }
    if (parsed.password() != null) {
      dataSource.setPassword(parsed.password());
    }
    dataSource.setSchema(SCHEMA_NAME);
    dataSource.setMaximumPoolSize(4);
    dataSource.setPoolName("marketdata-publisher-hikari");
    return dataSource;
  }

  /** Creates the service-local JDBC template. */
  @Bean
  JdbcTemplate jdbcTemplate(DataSource dataSource) {
    return new JdbcTemplate(dataSource);
  }

  /** Creates the local transaction manager used by snapshot publication. */
  @Bean
  PlatformTransactionManager transactionManager(DataSource dataSource) {
    return new DataSourceTransactionManager(dataSource);
  }

  /** Creates the deterministic source parser and normalizer. */
  @Bean
  MarketSnapshotImportService marketSnapshotImportService(ObjectMapper marketdataPublisherObjectMapper) {
    return new MarketSnapshotImportService(marketdataPublisherObjectMapper);
  }

  /** Creates snapshot persistence operations that remain inside application-owned transactions. */
  @Bean
  MarketSnapshotRepository marketSnapshotRepository(JdbcTemplate jdbcTemplate) {
    return new JdbcMarketSnapshotRepository(jdbcTemplate);
  }

  /** Creates the service-local transactional outbox adapter. */
  @Bean
  SnapshotOutbox snapshotOutbox(JdbcTemplate jdbcTemplate) {
    return new JdbcSnapshotOutbox(jdbcTemplate);
  }

  /** Creates the public transaction owner for immutable daily snapshot publication. */
  @Bean
  MarketSnapshotApplicationService marketSnapshotApplicationService(
      MarketSnapshotRepository marketSnapshotRepository,
      SnapshotOutbox snapshotOutbox,
      ObjectMapper marketdataPublisherObjectMapper,
      Clock marketdataPublisherClock) {
    final Supplier<UUID> uuidV7 = SimpleMatchUuids::uuidV7;
    return new MarketSnapshotApplicationService(
        marketSnapshotRepository,
        snapshotOutbox,
        marketdataPublisherObjectMapper,
        marketdataPublisherClock,
        uuidV7,
        uuidV7);
  }

  /** Exposes fail-closed readiness for missing or stale daily reference data. */
  @Bean("marketSnapshotReadyHealthIndicator")
  MarketSnapshotReadinessHealthIndicator marketSnapshotReadyHealthIndicator(
      MarketSnapshotRepository marketSnapshotRepository,
      Clock marketdataPublisherClock) {
    return new MarketSnapshotReadinessHealthIndicator(marketSnapshotRepository, marketdataPublisherClock);
  }
}
