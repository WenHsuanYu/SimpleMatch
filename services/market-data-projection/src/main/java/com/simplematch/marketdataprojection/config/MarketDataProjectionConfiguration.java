package com.simplematch.marketdataprojection.config;

import com.simplematch.config.PostgresJdbcUrl;
import com.simplematch.config.PostgresProperties;
import com.simplematch.marketdataprojection.cache.MarketDataSnapshotCache;
import com.simplematch.marketdataprojection.runtime.MarketDataProjectionApplicationService;
import com.simplematch.marketdataprojection.runtime.MarketDataProjectionHandler;
import com.simplematch.marketdataprojection.runtime.MarketDataProjectionRebuildService;
import com.simplematch.marketdataprojection.store.JdbcMarketDataProjectionStore;
import com.simplematch.marketdataprojection.store.MarketDataProjectionStore;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Clock;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Configures the projection's isolated PostgreSQL owner schema and local transaction seam. */
@Configuration(proxyBeanMethods = false)
public class MarketDataProjectionConfiguration {
  private static final String SCHEMA_NAME = "market_data_projection";

  /** Supplies UTC timestamps for projection and output metadata. */
  @Bean
  Clock marketDataProjectionClock() {
    return Clock.systemUTC();
  }

  /** Creates the service-local PostgreSQL pool from the platform DSN. */
  @Bean
  DataSource marketDataProjectionDataSource(PostgresProperties properties) {
    final PostgresJdbcUrl jdbcUrl = PostgresJdbcUrl.parse(properties.dsn());
    final HikariDataSource dataSource = new HikariDataSource();
    dataSource.setJdbcUrl(jdbcUrl.jdbcUrl());
    if (jdbcUrl.username() != null) {
      dataSource.setUsername(jdbcUrl.username());
    }
    if (jdbcUrl.password() != null) {
      dataSource.setPassword(jdbcUrl.password());
    }
    dataSource.setSchema(SCHEMA_NAME);
    dataSource.setMaximumPoolSize(4);
    dataSource.setPoolName("market-data-projection-hikari");
    return dataSource;
  }

  /** Creates the thin JDBC adapter used by the projection's local owner schema. */
  @Bean
  JdbcTemplate marketDataProjectionJdbcTemplate(DataSource marketDataProjectionDataSource) {
    return new JdbcTemplate(marketDataProjectionDataSource);
  }

  /**
   * Creates the local transaction manager used only by the application-owned projection outcome.
   */
  @Bean
  PlatformTransactionManager marketDataProjectionTransactionManager(
      DataSource marketDataProjectionDataSource) {
    return new DataSourceTransactionManager(marketDataProjectionDataSource);
  }

  /** Creates the durable top-five view and output-intent adapter for the configured stream. */
  @Bean
  MarketDataProjectionStore marketDataProjectionStore(
      JdbcTemplate marketDataProjectionJdbcTemplate, MarketDataProjectionProperties properties) {
    return new JdbcMarketDataProjectionStore(
        marketDataProjectionJdbcTemplate, properties.marketdataEvents().topic());
  }

  /** Creates the public transaction seam for final Matching Event projection. */
  @Bean
  MarketDataProjectionHandler marketDataProjectionHandler(
      MarketDataProjectionStore marketDataProjectionStore,
      PlatformTransactionManager marketDataProjectionTransactionManager,
      Clock marketDataProjectionClock) {
    return new MarketDataProjectionApplicationService(
        marketDataProjectionStore,
        new TransactionTemplate(marketDataProjectionTransactionManager),
        marketDataProjectionClock);
  }

  /**
   * Creates the explicit local rebuild operation; Kafka group offset reset remains an operator
   * action.
   */
  @Bean
  MarketDataProjectionRebuildService marketDataProjectionRebuildService(
      MarketDataProjectionStore marketDataProjectionStore,
      PlatformTransactionManager marketDataProjectionTransactionManager,
      Optional<MarketDataSnapshotCache> marketDataSnapshotCache) {
    return new MarketDataProjectionRebuildService(
        marketDataProjectionStore,
        new TransactionTemplate(marketDataProjectionTransactionManager),
        marketDataSnapshotCache);
  }
}
