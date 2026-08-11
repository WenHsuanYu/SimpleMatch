package com.simplematch.queryservice.config;

import com.simplematch.config.SimpleMatchDataSourceSettings;
import com.simplematch.queryservice.runtime.QueryProjectionApplicationService;
import com.simplematch.queryservice.runtime.QueryProjectionRebuildService;
import com.simplematch.queryservice.runtime.QueryReadCache;
import com.simplematch.queryservice.store.JdbcQueryProjectionStore;
import com.simplematch.queryservice.store.QueryProjectionStore;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Composes the query service's isolated DataSource, JDBC adapters, and local transactions. */
@Configuration(proxyBeanMethods = false)
public class QueryServicePersistenceConfiguration {
  /** Supplies timestamps for source checkpoints and read-model freshness. */
  @Bean
  Clock queryServiceClock() {
    return Clock.systemUTC();
  }

  /** Supplies only query-service pool policy; the shared adapter owns URL and credentials. */
  @Bean
  SimpleMatchDataSourceSettings queryServiceDataSourceSettings() {
    return new SimpleMatchDataSourceSettings("query_service", 4, "query-service-hikari");
  }

  /** Creates the thin JDBC adapter over the service-owned managed pool. */
  @Bean
  JdbcTemplate queryServiceJdbcTemplate(DataSource dataSource) {
    return new JdbcTemplate(dataSource);
  }

  /** Creates the application-owned local transaction manager. */
  @Bean
  PlatformTransactionManager queryServiceTransactionManager(DataSource dataSource) {
    return new DataSourceTransactionManager(dataSource);
  }

  /** Creates the durable read-model repository. */
  @Bean
  QueryProjectionStore queryProjectionStore(JdbcTemplate queryServiceJdbcTemplate) {
    return new JdbcQueryProjectionStore(queryServiceJdbcTemplate);
  }

  /** Creates the transaction-owning projection application service. */
  @Bean
  QueryProjectionApplicationService queryProjectionApplicationService(
      QueryProjectionStore queryProjectionStore) {
    return new QueryProjectionApplicationService(queryProjectionStore);
  }

  /** Creates the explicit operator rebuild seam. */
  @Bean
  QueryProjectionRebuildService queryProjectionRebuildService(
      QueryProjectionStore queryProjectionStore,
      PlatformTransactionManager queryServiceTransactionManager,
      QueryReadCache queryReadCache) {
    return new QueryProjectionRebuildService(
        queryProjectionStore,
        new TransactionTemplate(queryServiceTransactionManager),
        queryReadCache);
  }
}
