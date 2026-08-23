package com.simplematch.persistence.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.ByteString;
import com.simplematch.contracts.common.v2.Side;
import com.simplematch.contracts.common.v2.VenueInstrument;
import com.simplematch.contracts.matching.runtime.v1.ArtifactIdentity;
import com.simplematch.contracts.matching.runtime.v1.DeterministicEventConflictException;
import com.simplematch.contracts.matching.runtime.v1.FinalMatchingEventEnvelope;
import com.simplematch.contracts.matching.runtime.v1.MatchingEvent;
import com.simplematch.contracts.matching.runtime.v1.MatchingEventIdentityV1;
import com.simplematch.contracts.matching.runtime.v1.MatchingEventType;
import com.simplematch.contracts.matching.runtime.v1.TradeExecuted;
import com.simplematch.contracts.matching.runtime.v1.TradeLeg;
import com.simplematch.contracts.matching.runtime.v1.TradeLegState;
import com.simplematch.persistence.matching.MatchingEventPersistenceOutcome;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
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
  private static final String COMMAND_ID = "0198a001-0000-7000-8000-000000000001";
  private static final UUID COMMAND_UUID = UUID.fromString(COMMAND_ID);
  private static final byte[] EVENT_ID =
      MatchingEventIdentityV1.eventId("2026-08-11-regular", 0, COMMAND_UUID, 0);
  private static final byte[] TRADE_ID =
      MatchingEventIdentityV1.tradeId("2026-08-11-regular", 0, COMMAND_UUID, 0);

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
    final FinalMatchingEventEnvelope envelope = envelope(1_000_000);

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
    final FinalMatchingEventEnvelope envelope = envelope(1_000_000);

    assertThat(store.persist(envelope, 0, 42L, 1_000L))
        .isEqualTo(MatchingEventPersistenceOutcome.APPLIED);
    assertThat(store.persist(envelope, 0, 42L, 1_001L))
        .isEqualTo(MatchingEventPersistenceOutcome.DUPLICATE);

    assertThat(count("trades")).isEqualTo(1);
    assertThat(count("order_fills")).isEqualTo(2);
  }

  @Test
  void failsClosedWhenOneEventIdentityHasDifferentRawBytes() throws Exception {
    assertThat(store.persist(envelope(1_000_000), 0, 42L, 1_000L))
        .isEqualTo(MatchingEventPersistenceOutcome.APPLIED);

    assertThatThrownBy(() -> store.persist(envelope(1_000_001), 0, 42L, 1_001L))
        .isInstanceOf(DeterministicEventConflictException.class);
  }

  private int count(String table) {
    return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM persistence." + table, Integer.class);
  }

  private FinalMatchingEventEnvelope envelope(long priceUnits) throws Exception {
    final MatchingEvent event =
        MatchingEvent.newBuilder()
            .setSchemaVersion(1)
            .setIdentityVersion(1)
            .setEventId(ByteString.copyFrom(EVENT_ID))
            .setTradingSessionId("2026-08-11-regular")
            .setPartitionId(0)
            .setSourceCommandId(COMMAND_ID)
            .setSourceInputOffset(42L)
            .setOutputIndex(0)
            .setArtifactIdentity(ARTIFACT)
            .setRoutingAlgorithmVersion("stable-least-loaded-v1")
            .setEventType(MatchingEventType.MATCHING_EVENT_TYPE_TRADE_EXECUTED)
            .setTradeExecuted(
                TradeExecuted.newBuilder()
                    .setTradeId(ByteString.copyFrom(TRADE_ID))
                    .setMatchIndex(0)
                    .setInstrument(VenueInstrument.newBuilder().setVenueMic("XTAI").setSymbol("2330"))
                    .setAggressorSide(Side.SIDE_BUY)
                    .setQuantityShares(100L)
                    .setPriceUnits(priceUnits)
                    .setMaker(
                        leg(
                            "0198a001-0000-7000-8000-000000000011",
                            "0198a001-0000-7000-8000-0000000000aa",
                            Side.SIDE_SELL))
                    .setTaker(
                        leg(
                            "0198a001-0000-7000-8000-000000000012",
                            "0198a001-0000-7000-8000-0000000000aa",
                            Side.SIDE_BUY)))
            .build();
    return FinalMatchingEventEnvelope.parse(event.toByteArray());
  }

  private TradeLeg leg(String orderId, String accountId, Side side) {
    return TradeLeg.newBuilder()
        .setOrderId(orderId)
        .setAccountId(accountId)
        .setSide(side)
        .setCumulativeQuantityShares(100L)
        .setLeavesQuantityShares(0L)
        .setAveragePriceUnits(1_000_000L)
        .setResultingState(TradeLegState.TRADE_LEG_STATE_FILLED)
        .build();
  }
}
