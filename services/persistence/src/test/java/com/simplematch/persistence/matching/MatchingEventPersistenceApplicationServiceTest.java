package com.simplematch.persistence.matching;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.ByteString;
import com.simplematch.contracts.common.v2.Side;
import com.simplematch.contracts.common.v2.VenueInstrument;
import com.simplematch.contracts.matching.runtime.v1.ArtifactIdentity;
import com.simplematch.contracts.matching.runtime.v1.FinalMatchingEventEnvelope;
import com.simplematch.contracts.matching.runtime.v1.MatchingEvent;
import com.simplematch.contracts.matching.runtime.v1.MatchingEventIdentityV1;
import com.simplematch.contracts.matching.runtime.v1.MatchingEventType;
import com.simplematch.contracts.matching.runtime.v1.TradeExecuted;
import com.simplematch.contracts.matching.runtime.v1.TradeLeg;
import com.simplematch.contracts.matching.runtime.v1.TradeLegState;
import com.simplematch.persistence.store.JdbcMatchingEventStore;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/** Verifies the Persistence application Interface owns one atomic final-event database outcome. */
class MatchingEventPersistenceApplicationServiceTest {
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-08-11T00:00:00Z"), ZoneOffset.UTC);
  private static final ArtifactIdentity ARTIFACT =
      ArtifactIdentity.newBuilder()
          .setTradingDay("2026-08-11")
          .setContentSha256("7cd06c51691bcde248e606ed1adfaddc4bd10ece582a6803fd2f04155a032943")
          .build();
  private static final String TRADING_SESSION_ID = "2026-08-11-regular";
  private static final int PARTITION_ID = 0;
  private static final UUID SOURCE_COMMAND_ID =
      UUID.fromString("0198a001-0000-7000-8000-000000000001");
  private static final int OUTPUT_INDEX = 0;
  private static final int MATCH_INDEX = 0;

  private SingleConnectionDataSource dataSource;
  private JdbcTemplate jdbcTemplate;
  private MatchingEventPersistenceHandler handler;
  private TransactionTemplate transactions;

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
    handler =
        new MatchingEventPersistenceApplicationService(
            new JdbcMatchingEventStore(jdbcTemplate), CLOCK);
    transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
  }

  @AfterEach
  void tearDown() {
    dataSource.destroy();
  }

  @Test
  void crashBeforeCommitRollsBackInboxTradeFillsProjectionsAndProgress() throws Exception {
    final FinalMatchingEventEnvelope envelope = tradeEnvelope();

    assertThatThrownBy(
            () ->
                transactions.executeWithoutResult(
                    ignored -> {
                      assertThat(handler.persist(envelope, 0, 42L))
                          .isEqualTo(MatchingEventPersistenceOutcome.APPLIED);
                      throw new IllegalStateException("simulated crash before commit");
                    }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("simulated crash before commit");

    assertThat(count("matching_event_inbox")).isZero();
    assertThat(count("trades")).isZero();
    assertThat(count("order_fills")).isZero();
    assertThat(count("matching_order_projections")).isZero();
    assertThat(count("matching_consumer_progress")).isZero();
  }

  @Test
  void committedReplayAdvancesTransportProgressWithoutDuplicatingTradeFacts() throws Exception {
    final FinalMatchingEventEnvelope envelope = tradeEnvelope();
    final MatchingEventPersistenceOutcome first =
        transactions.execute(ignored -> handler.persist(envelope, 0, 42L));
    final MatchingEventPersistenceOutcome replay =
        transactions.execute(ignored -> handler.persist(envelope, 0, 43L));

    assertThat(first).isEqualTo(MatchingEventPersistenceOutcome.APPLIED);
    assertThat(replay).isEqualTo(MatchingEventPersistenceOutcome.DUPLICATE);
    assertThat(count("matching_event_inbox")).isEqualTo(1);
    assertThat(count("trades")).isEqualTo(1);
    assertThat(count("order_fills")).isEqualTo(2);
    assertThat(count("matching_order_projections")).isEqualTo(2);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT last_processed_offset FROM persistence.matching_consumer_progress "
                    + "WHERE partition_id = 0",
                Long.class))
        .isEqualTo(43L);
  }

  private int count(String table) {
    return jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM persistence." + table, Integer.class);
  }

  private FinalMatchingEventEnvelope tradeEnvelope() throws Exception {
    return FinalMatchingEventEnvelope.parse(
        MatchingEvent.newBuilder()
            .setSchemaVersion(1)
            .setIdentityVersion(1)
            .setEventId(
                ByteString.copyFrom(
                    MatchingEventIdentityV1.eventId(
                        TRADING_SESSION_ID, PARTITION_ID, SOURCE_COMMAND_ID, OUTPUT_INDEX)))
            .setTradingSessionId(TRADING_SESSION_ID)
            .setPartitionId(PARTITION_ID)
            .setSourceCommandId(SOURCE_COMMAND_ID.toString())
            .setSourceInputOffset(42L)
            .setOutputIndex(OUTPUT_INDEX)
            .setArtifactIdentity(ARTIFACT)
            .setRoutingAlgorithmVersion("stable-least-loaded-v1")
            .setEventType(MatchingEventType.MATCHING_EVENT_TYPE_TRADE_EXECUTED)
            .setTradeExecuted(
                TradeExecuted.newBuilder()
                    .setTradeId(
                        ByteString.copyFrom(
                            MatchingEventIdentityV1.tradeId(
                                TRADING_SESSION_ID,
                                PARTITION_ID,
                                SOURCE_COMMAND_ID,
                                MATCH_INDEX)))
                    .setInstrument(
                        VenueInstrument.newBuilder().setVenueMic("XTAI").setSymbol("2330"))
                    .setMaker(
                        leg(
                            "0198a001-0000-7000-8000-000000000011",
                            "0198a001-0000-7000-8000-0000000000aa",
                            Side.SIDE_SELL))
                    .setTaker(
                        leg(
                            "0198a001-0000-7000-8000-000000000012",
                            "0198a001-0000-7000-8000-0000000000bb",
                            Side.SIDE_BUY))
                    .setMatchIndex(MATCH_INDEX)
                    .setAggressorSide(Side.SIDE_BUY)
                    .setQuantityShares(100L)
                    .setPriceUnits(1_000_000L))
            .build()
            .toByteArray());
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
