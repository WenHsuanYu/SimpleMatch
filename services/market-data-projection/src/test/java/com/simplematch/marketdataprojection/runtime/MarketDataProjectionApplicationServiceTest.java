package com.simplematch.marketdataprojection.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.simplematch.contracts.common.v2.Side;
import com.simplematch.contracts.common.v2.VenueInstrument;
import com.simplematch.contracts.marketdata.runtime.v1.MarketDataSnapshot;
import com.simplematch.contracts.matching.runtime.v1.ArtifactIdentity;
import com.simplematch.contracts.matching.runtime.v1.DeterministicEventConflictException;
import com.simplematch.contracts.matching.runtime.v1.FinalMatchingEventEnvelope;
import com.simplematch.contracts.matching.runtime.v1.MatchingEvent;
import com.simplematch.contracts.matching.runtime.v1.MatchingEventType;
import com.simplematch.contracts.matching.runtime.v1.OrderRested;
import com.simplematch.contracts.matching.runtime.v1.TradeExecuted;
import com.simplematch.contracts.matching.runtime.v1.TradeLeg;
import com.simplematch.marketdataprojection.store.JdbcMarketDataProjectionStore;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/** Verifies the public transaction seam for the rebuildable runtime market-data projection. */
class MarketDataProjectionApplicationServiceTest {
  private static final ArtifactIdentity ARTIFACT =
      ArtifactIdentity.newBuilder()
          .setTradingDay("2026-08-11")
          .setContentSha256("7cd06c51691bcde248e606ed1adfaddc4bd10ece582a6803fd2f04155a032943")
          .build();
  private static final String BUY_ORDER_ID = "0198a001-0000-7000-8000-000000000011";
  private static final String SELL_ORDER_ID = "0198a001-0000-7000-8000-000000000012";
  private static final String ACCOUNT_ID = "0198a001-0000-7000-8000-0000000000aa";

  private JdbcTemplate jdbcTemplate;
  private SingleConnectionDataSource dataSource;
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
    service =
        new MarketDataProjectionApplicationService(
            new JdbcMarketDataProjectionStore(jdbcTemplate),
            new TransactionTemplate(new DataSourceTransactionManager(dataSource)),
            Clock.fixed(Instant.parse("2026-08-11T01:02:03Z"), ZoneOffset.UTC));
  }

  @AfterEach
  void tearDown() {
    dataSource.destroy();
  }

  @Test
  void publishesACompleteTopFiveSnapshotForEachAppliedMatchingEvent() throws Exception {
    service.project(rested("a".repeat(64), BUY_ORDER_ID, Side.SIDE_BUY, 100_000L, 100L), 0, 10L);
    service.project(rested("b".repeat(64), SELL_ORDER_ID, Side.SIDE_SELL, 102_000L, 200L), 0, 11L);

    final MarketDataProjectionResult result = service.project(trade("c".repeat(64)), 0, 12L);

    assertThat(result.applied()).isTrue();
    final MarketDataSnapshotView snapshot = result.snapshot().orElseThrow();
    assertThat(snapshot.instrumentSequence()).isEqualTo(3L);
    assertThat(snapshot.lastTrade()).contains(new LastTrade(100_000L, 100L));
    assertThat(snapshot.bids()).isEmpty();
    assertThat(snapshot.asks()).containsExactly(new PriceLevel(102_000L, 200L));
    assertThat(snapshot.isCompleteSnapshot()).isTrue();
    assertThat(count("market_data_events_outbox")).isEqualTo(3);
    final MarketDataSnapshot published =
        MarketDataSnapshot.parseFrom(
            jdbcTemplate.queryForObject(
                "SELECT payload FROM market_data_projection.market_data_events_outbox "
                    + "WHERE source_matching_event_id = ?",
                byte[].class,
                HexFormat.of().parseHex("c".repeat(64))));
    assertThat(published.getSchemaVersion()).isEqualTo(1);
    assertThat(published.getIsSnapshot()).isTrue();
    assertThat(published.getInstrumentSequence()).isEqualTo(3L);
    assertThat(published.getAsksList())
        .singleElement()
        .satisfies(level -> assertThat(level.getPriceUnits()).isEqualTo(102_000L));
  }

  @Test
  void acceptsAnExactReplayWithoutAdvancingItsInstrumentSequenceOrOutbox() throws Exception {
    final FinalMatchingEventEnvelope event =
        rested("a".repeat(64), BUY_ORDER_ID, Side.SIDE_BUY, 100_000L, 100L);

    assertThat(service.project(event, 0, 10L).applied()).isTrue();
    assertThat(service.project(event, 0, 10L).applied()).isFalse();

    assertThat(count("market_data_events_outbox")).isEqualTo(1);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT instrument_sequence FROM market_data_projection.instrument_market_data "
                    + "WHERE venue_mic = 'XTAI' AND symbol = '2330'",
                Long.class))
        .isEqualTo(1L);
  }

  @Test
  void refusesOneEventIdentityWithDifferentRawBytesWithoutPublishingAnotherSnapshot() throws Exception {
    service.project(rested("a".repeat(64), BUY_ORDER_ID, Side.SIDE_BUY, 100_000L, 100L), 0, 10L);

    assertThatThrownBy(
            () ->
                service.project(
                    rested("a".repeat(64), BUY_ORDER_ID, Side.SIDE_BUY, 101_000L, 100L), 0, 10L))
        .isInstanceOf(DeterministicEventConflictException.class);

    assertThat(count("market_data_events_outbox")).isEqualTo(1);
  }

  @Test
  void marksTheProjectionForResyncInsteadOfSilentlyApplyingAnOffsetGap() throws Exception {
    service.project(rested("a".repeat(64), BUY_ORDER_ID, Side.SIDE_BUY, 100_000L, 100L), 0, 10L);

    assertThatThrownBy(
            () ->
                service.project(
                    rested("b".repeat(64), SELL_ORDER_ID, Side.SIDE_SELL, 102_000L, 200L),
                    0,
                    12L))
        .isInstanceOf(MarketDataProjectionGapException.class);

    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT recovery_state FROM market_data_projection.partition_projection_progress "
                    + "WHERE partition_id = 0",
                String.class))
        .isEqualTo("RESYNC_REQUIRED");
    assertThat(count("market_data_events_outbox")).isEqualTo(1);
  }

  @Test
  void rebuildResetClearsOnlyReconstructibleProjectionStateBeforeKafkaReplay() throws Exception {
    service.project(rested("a".repeat(64), BUY_ORDER_ID, Side.SIDE_BUY, 100_000L, 100L), 0, 10L);
    final MarketDataProjectionRebuildService rebuildService =
        new MarketDataProjectionRebuildService(
            new JdbcMarketDataProjectionStore(jdbcTemplate),
            new TransactionTemplate(new DataSourceTransactionManager(dataSource)));

    rebuildService.resetForReplay();

    assertThat(count("matching_event_inbox")).isZero();
    assertThat(count("partition_projection_progress")).isZero();
    assertThat(count("order_book_entries")).isZero();
    assertThat(count("instrument_market_data")).isZero();
    assertThat(count("market_data_events_outbox")).isZero();
  }

  private int count(String table) {
    return jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM market_data_projection." + table, Integer.class);
  }

  private FinalMatchingEventEnvelope rested(
      String eventId, String orderId, Side side, long priceUnits, long leaves) throws Exception {
    return envelope(
        eventId,
        MatchingEventType.MATCHING_EVENT_TYPE_ORDER_RESTED,
        MatchingEvent.newBuilder()
            .setOrderRested(
                OrderRested.newBuilder()
                    .setOrderId(orderId)
                    .setAccountId(ACCOUNT_ID)
                    .setInstrument(instrument())
                    .setSide(side)
                    .setRestingPriceUnits(priceUnits)
                    .setLeavesQuantityShares(leaves)));
  }

  private FinalMatchingEventEnvelope trade(String eventId) throws Exception {
    return envelope(
        eventId,
        MatchingEventType.MATCHING_EVENT_TYPE_TRADE_EXECUTED,
        MatchingEvent.newBuilder()
            .setTradeExecuted(
                TradeExecuted.newBuilder()
                    .setTradeId("d".repeat(64))
                    .setInstrument(instrument())
                    .setMaker(leg(BUY_ORDER_ID, Side.SIDE_BUY, 100_000L, 0L))
                    .setTaker(leg(SELL_ORDER_ID, Side.SIDE_SELL, 100_000L, 100L))));
  }

  private FinalMatchingEventEnvelope envelope(
      String eventId, MatchingEventType type, MatchingEvent.Builder builder) throws Exception {
    return FinalMatchingEventEnvelope.parse(
        builder
            .setSchemaVersion(1)
            .setIdentityVersion(1)
            .setEventId(eventId)
            .setTradingSessionId("2026-08-11-regular")
            .setPartitionId(0)
            .setSourceCommandId("0198a001-0000-7000-8000-000000000001")
            .setSourceInputOffset(10L)
            .setOutputIndex(0)
            .setArtifactIdentity(ARTIFACT)
            .setRoutingAlgorithmVersion("stable-least-loaded-v1")
            .setEventType(type)
            .build()
            .toByteArray());
  }

  private VenueInstrument instrument() {
    return VenueInstrument.newBuilder().setVenueMic("XTAI").setSymbol("2330").build();
  }

  private TradeLeg leg(String orderId, Side side, long priceUnits, long leaves) {
    return TradeLeg.newBuilder()
        .setOrderId(orderId)
        .setAccountId(ACCOUNT_ID)
        .setSide(side)
        .setQuantityShares(100L)
        .setPriceUnits(priceUnits)
        .setCumulativeQuantityShares(100L)
        .setLeavesQuantityShares(leaves)
        .setAveragePriceUnits(priceUnits)
        .build();
  }
}
