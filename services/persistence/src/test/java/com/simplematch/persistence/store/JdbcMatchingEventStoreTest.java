package com.simplematch.persistence.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.simplematch.contracts.common.v2.Side;
import com.simplematch.contracts.common.v2.VenueInstrument;
import com.simplematch.contracts.matching.runtime.v1.ArtifactIdentity;
import com.simplematch.contracts.matching.runtime.v1.DeterministicEventConflictException;
import com.simplematch.contracts.matching.runtime.v1.FinalMatchingEventEnvelope;
import com.simplematch.contracts.matching.runtime.v1.MatchingEvent;
import com.simplematch.contracts.matching.runtime.v1.MatchingEventType;
import com.simplematch.contracts.matching.runtime.v1.TradeExecuted;
import com.simplematch.contracts.matching.runtime.v1.TradeLeg;
import com.simplematch.persistence.matching.MatchingEventPersistenceOutcome;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/** Verifies the Persistence inbox transaction's durable final-event facts. */
class JdbcMatchingEventStoreTest {
  private static final ArtifactIdentity ARTIFACT =
      ArtifactIdentity.newBuilder()
          .setTradingDay("2026-08-11")
          .setContentSha256("7cd06c51691bcde248e606ed1adfaddc4bd10ece582a6803fd2f04155a032943")
          .build();
  private static final String EVENT_ID = "e".repeat(64);
  private static final String TRADE_ID = "d".repeat(64);

  private JdbcTemplate jdbcTemplate;
  private JdbcMatchingEventStore store;
  private SingleConnectionDataSource dataSource;

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
        .locations("classpath:db/migration/persistence")
        .load()
        .migrate();
    jdbcTemplate = new JdbcTemplate(dataSource);
    store = new JdbcMatchingEventStore(jdbcTemplate);
  }

  @AfterEach
  void tearDown() {
    dataSource.destroy();
  }

  @Test
  void storesTheInboxTradeBothFillLegsAndOrderProjectionsAtomically() throws Exception {
    final FinalMatchingEventEnvelope envelope = envelope(EVENT_ID, 1_000_000);

    assertThat(store.persist(envelope, 0, 42L, 1_000L))
        .isEqualTo(MatchingEventPersistenceOutcome.APPLIED);

    assertThat(count("matching_event_inbox")).isEqualTo(1);
    assertThat(count("trades")).isEqualTo(1);
    assertThat(count("order_fills")).isEqualTo(2);
    assertThat(count("matching_order_projections")).isEqualTo(2);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT last_processed_offset FROM persistence.matching_consumer_progress "
                    + "WHERE consumer_name = 'persistence-matching-events' AND partition_id = 0",
                Long.class))
        .isEqualTo(42L);
  }

  @Test
  void deduplicatesAReplayedFinalEventWithoutDuplicatingFacts() throws Exception {
    final FinalMatchingEventEnvelope envelope = envelope(EVENT_ID, 1_000_000);

    assertThat(store.persist(envelope, 0, 42L, 1_000L))
        .isEqualTo(MatchingEventPersistenceOutcome.APPLIED);
    assertThat(store.persist(envelope, 0, 42L, 1_001L))
        .isEqualTo(MatchingEventPersistenceOutcome.DUPLICATE);

    assertThat(count("trades")).isEqualTo(1);
    assertThat(count("order_fills")).isEqualTo(2);
  }

  @Test
  void failsClosedWhenOneEventIdentityHasDifferentRawBytes() throws Exception {
    assertThat(store.persist(envelope(EVENT_ID, 1_000_000), 0, 42L, 1_000L))
        .isEqualTo(MatchingEventPersistenceOutcome.APPLIED);

    assertThatThrownBy(() -> store.persist(envelope(EVENT_ID, 1_000_001), 0, 42L, 1_001L))
        .isInstanceOf(DeterministicEventConflictException.class);
  }

  private int count(String table) {
    return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM persistence." + table, Integer.class);
  }

  private FinalMatchingEventEnvelope envelope(String eventId, long priceUnits) throws Exception {
    final MatchingEvent event =
        MatchingEvent.newBuilder()
            .setSchemaVersion(1)
            .setIdentityVersion(1)
            .setEventId(eventId)
            .setTradingSessionId("2026-08-11-regular")
            .setPartitionId(0)
            .setSourceCommandId("0198a001-0000-7000-8000-000000000001")
            .setSourceInputOffset(42L)
            .setOutputIndex(0)
            .setArtifactIdentity(ARTIFACT)
            .setRoutingAlgorithmVersion("stable-least-loaded-v1")
            .setEventType(MatchingEventType.MATCHING_EVENT_TYPE_TRADE_EXECUTED)
            .setTradeExecuted(
                TradeExecuted.newBuilder()
                    .setTradeId(TRADE_ID)
                    .setInstrument(
                        VenueInstrument.newBuilder().setVenueMic("XTAI").setSymbol("2330"))
                    .setMaker(
                        leg(
                            "0198a001-0000-7000-8000-000000000011",
                            "0198a001-0000-7000-8000-0000000000aa",
                            Side.SIDE_SELL,
                            priceUnits))
                    .setTaker(
                        leg(
                            "0198a001-0000-7000-8000-000000000012",
                            "0198a001-0000-7000-8000-0000000000aa",
                            Side.SIDE_BUY,
                            priceUnits)))
            .build();
    return FinalMatchingEventEnvelope.parse(event.toByteArray());
  }

  private TradeLeg leg(String orderId, String accountId, Side side, long priceUnits) {
    return TradeLeg.newBuilder()
        .setOrderId(orderId)
        .setAccountId(accountId)
        .setSide(side)
        .setQuantityShares(100L)
        .setPriceUnits(priceUnits)
        .setCumulativeQuantityShares(100L)
        .setLeavesQuantityShares(0L)
        .build();
  }
}
