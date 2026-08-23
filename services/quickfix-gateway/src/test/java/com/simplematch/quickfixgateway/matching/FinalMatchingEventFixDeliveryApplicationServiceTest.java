package com.simplematch.quickfixgateway.matching;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.ByteString;
import com.simplematch.contracts.common.v2.OrderType;
import com.simplematch.contracts.common.v2.Side;
import com.simplematch.contracts.common.v2.TimeInForce;
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
import com.simplematch.quickfixgateway.fix.OrderSessionRegistry;
import com.simplematch.quickfixgateway.store.JdbcFinalFixDeliveryStore;
import com.simplematch.quickfixgateway.wal.FixSessionIdentity;
import com.simplematch.quickfixgateway.wal.RawFixMessage;
import com.simplematch.quickfixgateway.wal.WalCommand;
import com.simplematch.quickfixgateway.wal.WalMetadata;
import com.simplematch.quickfixgateway.wal.WalOrderReference;
import com.simplematch.quickfixgateway.wal.WalOrderTerms;
import com.simplematch.quickfixgateway.wal.WalRecord;
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
import quickfix.SessionID;

/** Verifies one Gateway transaction owns final-event inbox, dual delivery intents, and progress. */
class FinalMatchingEventFixDeliveryApplicationServiceTest {
  private static final ArtifactIdentity ARTIFACT =
      ArtifactIdentity.newBuilder()
          .setTradingDay("2026-08-11")
          .setContentSha256("7cd06c51691bcde248e606ed1adfaddc4bd10ece582a6803fd2f04155a032943")
          .build();
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-08-11T00:00:00Z"), ZoneOffset.UTC);
  private static final String COMMAND_ID = "0198a001-0000-7000-8000-000000000001";
  private static final UUID COMMAND_UUID = UUID.fromString(COMMAND_ID);
  private static final String TRADE_ID =
      HexFormat.of().formatHex(
          MatchingEventIdentityV1.tradeId("2026-08-11-regular", 0, COMMAND_UUID, 0));
  private static final String MAKER_ORDER_ID = "0198a001-0000-7000-8000-000000000011";
  private static final String TAKER_ORDER_ID = "0198a001-0000-7000-8000-000000000012";
  private static final String MAKER_ACCOUNT_ID = "0198a001-0000-7000-8000-0000000000aa";
  private static final String TAKER_ACCOUNT_ID = "0198a001-0000-7000-8000-0000000000bb";

  private SingleConnectionDataSource dataSource;
  private JdbcTemplate jdbcTemplate;
  private JdbcFinalFixDeliveryStore store;
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
        .locations("classpath:db/migration/quickfix-gateway")
        .load()
        .migrate();
    jdbcTemplate = new JdbcTemplate(dataSource);
    store = new JdbcFinalFixDeliveryStore(jdbcTemplate);
    transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
  }

  @AfterEach
  void tearDown() {
    dataSource.destroy();
  }

  @Test
  void storesOneInboxRowAndMakerTakerIntentsBeforeAdvancingProgress() throws Exception {
    final FinalMatchingEventFixDeliveryApplicationService service = service(completeRegistry());

    assertThat(persist(service, tradeEnvelope(1_000_000L)))
        .isEqualTo(FinalMatchingEventFixDeliveryOutcome.APPLIED);

    assertThat(count("matching_event_inbox")).isEqualTo(1);
    assertThat(count("fix_delivery_intents")).isEqualTo(2);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT last_processed_offset FROM quickfix_gateway.matching_consumer_progress "
                    + "WHERE consumer_name = 'quickfix-final-matching-events' AND partition_id = 0",
                Long.class))
        .isEqualTo(42L);
    assertThat(store.findPending(10))
        .extracting(intent -> intent.report().executionId())
        .containsExactly(TRADE_ID + "-maker", TRADE_ID + "-taker");
  }

  @Test
  void replayedBytesProduceNoSecondDeliveryIntent() throws Exception {
    final FinalMatchingEventFixDeliveryApplicationService service = service(completeRegistry());
    final FinalMatchingEventEnvelope envelope = tradeEnvelope(1_000_000L);

    assertThat(persist(service, envelope)).isEqualTo(FinalMatchingEventFixDeliveryOutcome.APPLIED);
    assertThat(persist(service, envelope)).isEqualTo(FinalMatchingEventFixDeliveryOutcome.DUPLICATE);

    assertThat(count("fix_delivery_intents")).isEqualTo(2);
  }

  @Test
  void conflictingRawBytesWithTheSameEventIdentityFailClosed() throws Exception {
    final FinalMatchingEventFixDeliveryApplicationService service = service(completeRegistry());

    assertThat(persist(service, tradeEnvelope(1_000_000L)))
        .isEqualTo(FinalMatchingEventFixDeliveryOutcome.APPLIED);

    assertThatThrownBy(() -> persist(service, tradeEnvelope(1_000_001L)))
        .isInstanceOf(DeterministicEventConflictException.class);
  }

  @Test
  void missingRecipientSessionRollsBackTheClaimedInbox() throws Exception {
    final OrderSessionRegistry incompleteRegistry = new OrderSessionRegistry();
    register(incompleteRegistry, MAKER_ORDER_ID, MAKER_ACCOUNT_ID, Side.SIDE_SELL, "M-1");
    final FinalMatchingEventFixDeliveryApplicationService service = service(incompleteRegistry);

    assertThatThrownBy(() -> persist(service, tradeEnvelope(1_000_000L)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no owning FIX session");

    assertThat(count("matching_event_inbox")).isZero();
    assertThat(count("fix_delivery_intents")).isZero();
    assertThat(count("matching_consumer_progress")).isZero();
  }

  private FinalMatchingEventFixDeliveryApplicationService service(OrderSessionRegistry registry) {
    return new FinalMatchingEventFixDeliveryApplicationService(
        store, new FinalMatchingEventFixDeliveryPlanner(registry), CLOCK);
  }

  private FinalMatchingEventFixDeliveryOutcome persist(
      FinalMatchingEventFixDeliveryApplicationService service, FinalMatchingEventEnvelope envelope) {
    return transactions.execute(status -> service.persist(envelope, 0, 42L));
  }

  private OrderSessionRegistry completeRegistry() {
    final OrderSessionRegistry registry = new OrderSessionRegistry();
    register(registry, MAKER_ORDER_ID, MAKER_ACCOUNT_ID, Side.SIDE_SELL, "M-1");
    register(registry, TAKER_ORDER_ID, TAKER_ACCOUNT_ID, Side.SIDE_BUY, "T-1");
    return registry;
  }

  private void register(
      OrderSessionRegistry registry, String orderId, String accountId, Side side, String clientOrderId) {
    registry.registerAcceptedOrder(
        new SessionID("FIX.4.4", "SIMPLEMATCH", "CLIENT"),
        new WalRecord(
            new WalMetadata("v1", UUID.randomUUID().toString(), 1L, "quickfix-gateway"),
            new FixSessionIdentity("CLIENT", "SIMPLEMATCH"),
            new WalOrderReference(orderId, clientOrderId, "", accountId),
            new WalCommand.NewOrder(
                new WalOrderTerms(
                    "2330",
                    side,
                    "100",
                    "100",
                    OrderType.ORDER_TYPE_LIMIT,
                    TimeInForce.TIME_IN_FORCE_ROD)),
            new RawFixMessage("raw")),
        'A');
  }

  private int count(String table) {
    return jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM quickfix_gateway." + table, Integer.class);
  }

  private FinalMatchingEventEnvelope tradeEnvelope(long priceUnits) throws Exception {
    final TradeLeg maker = leg(MAKER_ORDER_ID, MAKER_ACCOUNT_ID, Side.SIDE_SELL);
    final TradeLeg taker = leg(TAKER_ORDER_ID, TAKER_ACCOUNT_ID, Side.SIDE_BUY);
    return FinalMatchingEventEnvelope.parse(
        MatchingEvent.newBuilder()
            .setSchemaVersion(1)
            .setIdentityVersion(1)
            .setEventId(ByteString.copyFrom(MatchingEventIdentityV1.eventId("2026-08-11-regular", 0, COMMAND_UUID, 0)))
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
                    .setTradeId(ByteString.copyFrom(MatchingEventIdentityV1.tradeId("2026-08-11-regular", 0, COMMAND_UUID, 0)))
                    .setMatchIndex(0)
                    .setInstrument(VenueInstrument.newBuilder().setVenueMic("XTAI").setSymbol("2330"))
                    .setAggressorSide(Side.SIDE_BUY)
                    .setQuantityShares(100L)
                    .setPriceUnits(priceUnits)
                    .setMaker(maker)
                    .setTaker(taker))
            .build()
            .toByteArray());
  }

  private TradeLeg leg(String orderId, String accountId, Side side) {
    return TradeLeg.newBuilder()
        .setOrderId(orderId)
        .setAccountId(accountId)
        .setSide(side)
        .setAveragePriceUnits(1_000_000L)
        .setCumulativeQuantityShares(100L)
        .setLeavesQuantityShares(0L)
        .setResultingState(TradeLegState.TRADE_LEG_STATE_FILLED)
        .build();
  }
}
