package com.simplematch.marketdataprojection.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.simplematch.contracts.common.v2.Side;
import com.simplematch.contracts.common.v2.VenueInstrument;
import com.simplematch.contracts.matching.runtime.v1.ArtifactIdentity;
import com.simplematch.contracts.matching.runtime.v1.FinalMatchingEventEnvelope;
import com.simplematch.contracts.matching.runtime.v1.MatchingEvent;
import com.simplematch.contracts.matching.runtime.v1.MatchingEventType;
import com.simplematch.contracts.matching.runtime.v1.OrderRested;
import com.simplematch.marketdataprojection.cache.MarketDataSnapshotCache;
import com.simplematch.marketdataprojection.cache.MarketDataSnapshotCacheEntry;
import com.simplematch.marketdataprojection.cache.MarketDataSnapshotCacheRefresher;
import com.simplematch.marketdataprojection.kafka.MarketDataEventPublisher;
import com.simplematch.marketdataprojection.kafka.MarketDataOutboxDispatcher;
import com.simplematch.marketdataprojection.kafka.MarketDataOutboxRecord;
import com.simplematch.marketdataprojection.store.JdbcMarketDataProjectionStore;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/** Verifies that output and cache adapters recover independently from the durable projection state. */
class RuntimeMarketDataPublicationTest {
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-08-11T01:02:03Z"), ZoneOffset.UTC);
  private JdbcTemplate jdbcTemplate;
  private SingleConnectionDataSource dataSource;
  private JdbcMarketDataProjectionStore store;
  private MarketDataProjectionApplicationService service;

  @BeforeEach
  void setUp() {
    dataSource =
        new SingleConnectionDataSource(
            "jdbc:h2:mem:"
                + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
            "sa",
            "",
            true);
    dataSource.setDriverClassName("org.h2.Driver");
    Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration/market-data-projection")
        .load()
        .migrate();
    jdbcTemplate = new JdbcTemplate(dataSource);
    store = new JdbcMarketDataProjectionStore(jdbcTemplate);
    service =
        new MarketDataProjectionApplicationService(
            store, new TransactionTemplate(new DataSourceTransactionManager(dataSource)), CLOCK);
  }

  @AfterEach
  void tearDown() {
    dataSource.destroy();
  }

  @Test
  void publishesOneDurableSnapshotIntentAndMarksItOnlyAfterProducerSuccess() throws Exception {
    service.project(rested("e".repeat(64)), 0, 10L);
    final List<MarketDataOutboxRecord> published = new ArrayList<>();
    final MarketDataOutboxDispatcher dispatcher =
        new MarketDataOutboxDispatcher(store, published::add, CLOCK, 10);

    dispatcher.dispatchPending();

    assertThat(published).singleElement().satisfies(record -> assertThat(record.key()).isEqualTo("XTAI:2330"));
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT published_at_unix_ms FROM market_data_projection.market_data_events_outbox",
                Long.class))
        .isEqualTo(CLOCK.millis());
  }

  @Test
  void leavesTheDurableIntentPendingWhenTheKafkaPublisherFails() throws Exception {
    service.project(rested("e".repeat(64)), 0, 10L);
    final MarketDataEventPublisher failingPublisher =
        record -> {
          throw new IllegalStateException("Kafka is unavailable");
        };
    final MarketDataOutboxDispatcher dispatcher =
        new MarketDataOutboxDispatcher(store, failingPublisher, CLOCK, 10);

    dispatcher.dispatchPending();

    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT published_at_unix_ms FROM market_data_projection.market_data_events_outbox",
                Long.class))
        .isNull();
  }

  @Test
  void retainsTheProjectionWhenRedisIsUnavailableAndRepairsTheCacheLater() throws Exception {
    service.project(rested("e".repeat(64)), 0, 10L);
    final MarketDataSnapshotCache failingCache =
        entry -> {
          throw new IllegalStateException("Redis is unavailable");
        };
    final MarketDataSnapshotCacheRefresher failingRefresher =
        new MarketDataSnapshotCacheRefresher(store, failingCache, 10);

    failingRefresher.refreshPending();

    assertThat(redisPending()).isTrue();
    final List<MarketDataSnapshotCacheEntry> cached = new ArrayList<>();
    final MarketDataSnapshotCacheRefresher successfulRefresher =
        new MarketDataSnapshotCacheRefresher(store, cached::add, 10);
    successfulRefresher.refreshPending();

    assertThat(cached).singleElement().satisfies(entry -> assertThat(entry.redisKey()).isEqualTo("marketdata:snapshot:XTAI:2330"));
    assertThat(redisPending()).isFalse();
  }

  private boolean redisPending() {
    return jdbcTemplate.queryForObject(
        "SELECT redis_snapshot_pending FROM market_data_projection.instrument_market_data",
        Boolean.class);
  }

  private FinalMatchingEventEnvelope rested(String eventId) throws Exception {
    return FinalMatchingEventEnvelope.parse(
        MatchingEvent.newBuilder()
            .setSchemaVersion(1)
            .setIdentityVersion(1)
            .setEventId(eventId)
            .setTradingSessionId("2026-08-11-regular")
            .setPartitionId(0)
            .setSourceCommandId("0198a001-0000-7000-8000-000000000001")
            .setSourceInputOffset(10L)
            .setOutputIndex(0)
            .setArtifactIdentity(
                ArtifactIdentity.newBuilder()
                    .setTradingDay("2026-08-11")
                    .setContentSha256(
                        "7cd06c51691bcde248e606ed1adfaddc4bd10ece582a6803fd2f04155a032943"))
            .setRoutingAlgorithmVersion("stable-least-loaded-v1")
            .setEventType(MatchingEventType.MATCHING_EVENT_TYPE_ORDER_RESTED)
            .setOrderRested(
                OrderRested.newBuilder()
                    .setOrderId("0198a001-0000-7000-8000-000000000011")
                    .setAccountId("0198a001-0000-7000-8000-0000000000aa")
                    .setInstrument(
                        VenueInstrument.newBuilder().setVenueMic("XTAI").setSymbol("2330"))
                    .setSide(Side.SIDE_BUY)
                    .setLeavesQuantityShares(100L)
                    .setRestingPriceUnits(100_000L))
            .build()
            .toByteArray());
  }
}
