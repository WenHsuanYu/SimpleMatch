package com.simplematch.queryservice.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.simplematch.contracts.account.v2.AccountLifecycleEvent;
import com.simplematch.contracts.account.v2.AccountLifecycleState;
import com.simplematch.contracts.common.v2.EventMetadata;
import com.simplematch.contracts.common.v2.TwdNotional;
import com.simplematch.queryservice.store.JdbcQueryProjectionStore;
import com.simplematch.queryservice.store.QueryProjectionSource;
import com.simplematch.queryservice.store.QueryProjectionStore;
import java.util.Optional;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

class QueryProjectionRebuildServiceTest {
  private static final String ACCOUNT_ID = "0198a001-0000-7000-8000-000000000003";

  @Test
  void retainsCommittedResetWhenDisposableCacheCleanupFails() {
    final DriverManagerDataSource dataSource = dataSource();
    final QueryProjectionStore store = new JdbcQueryProjectionStore(new JdbcTemplate(dataSource));
    final AccountLifecycleEvent event = accountEvent();
    store.projectAccountLifecycle(
        event,
        event.toByteArray(),
        new QueryProjectionSource("account.lifecycle", 0, 0L, 100L));
    final QueryProjectionRebuildService rebuildService =
        new QueryProjectionRebuildService(
            store,
            new TransactionTemplate(new DataSourceTransactionManager(dataSource)),
            new FailingCache());

    assertThatThrownBy(rebuildService::resetForReplay)
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("redis unavailable");

    assertThat(store.findAccountSummary(ACCOUNT_ID)).isEmpty();
    assertThat(store.freshness().partitions()).isEmpty();
  }

  private DriverManagerDataSource dataSource() {
    final DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName("org.h2.Driver");
    dataSource.setUrl(
        "jdbc:h2:mem:queryrebuild"
            + UUID.randomUUID().toString().replace("-", "")
            + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;"
            + "INIT=CREATE SCHEMA IF NOT EXISTS query_service\\;"
            + "SET SCHEMA query_service");
    Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration/query-service")
        .load()
        .migrate();
    return dataSource;
  }

  private AccountLifecycleEvent accountEvent() {
    return AccountLifecycleEvent.newBuilder()
        .setMetadata(
            EventMetadata.newBuilder()
                .setSchemaVersion("v2")
                .setEventId("0198a001-0000-7000-8000-000000000010")
                .setCreatedAtUnixMs(100L)
                .setSourceService("account-service"))
        .setReservationId("0198a001-0000-7000-8000-000000000002")
        .setOrderId("0198a001-0000-7000-8000-000000000002")
        .setAccountId(ACCOUNT_ID)
        .setState(AccountLifecycleState.ACCOUNT_LIFECYCLE_STATE_RESERVED)
        .setReservedNotional(TwdNotional.newBuilder().setUnits(1_000L))
        .build();
  }

  private static final class FailingCache implements QueryReadCache {
    @Override
    public Optional<JsonNode> get(String key) {
      return Optional.empty();
    }

    @Override
    public void put(String key, Object value) {}

    @Override
    public void clear() {
      throw new IllegalStateException("redis unavailable");
    }
  }
}
