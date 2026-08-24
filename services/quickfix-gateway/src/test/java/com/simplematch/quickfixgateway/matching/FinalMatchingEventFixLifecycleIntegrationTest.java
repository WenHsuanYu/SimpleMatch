package com.simplematch.quickfixgateway.matching;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.ByteString;
import com.simplematch.contracts.common.v2.OrderType;
import com.simplematch.contracts.common.v2.Side;
import com.simplematch.contracts.common.v2.TimeInForce;
import com.simplematch.contracts.common.v2.VenueInstrument;
import com.simplematch.contracts.matching.runtime.v1.ArtifactIdentity;
import com.simplematch.contracts.matching.runtime.v1.CancellationReason;
import com.simplematch.contracts.matching.runtime.v1.FinalMatchingEventEnvelope;
import com.simplematch.contracts.matching.runtime.v1.MatchingEvent;
import com.simplematch.contracts.matching.runtime.v1.MatchingEventIdentityV1;
import com.simplematch.contracts.matching.runtime.v1.MatchingEventType;
import com.simplematch.contracts.matching.runtime.v1.OrderRested;
import com.simplematch.contracts.matching.runtime.v1.OrderTerminal;
import com.simplematch.quickfixgateway.fix.FinalFixDeliveryIntent;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import quickfix.SessionID;

/** Verifies durable FIX lifecycle reports through the final-event application Interface. */
class FinalMatchingEventFixLifecycleIntegrationTest {
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
  private static final String ACCOUNT_ID = "0198a001-0000-7000-8000-0000000000aa";
  private static final String IOC_ORDER_ID = "0198a001-0000-7000-8000-000000000011";
  private static final String FOK_ORDER_ID = "0198a001-0000-7000-8000-000000000012";
  private static final String ROD_ORDER_ID = "0198a001-0000-7000-8000-000000000013";
  private static final String EXPIRED_ORDER_ID = "0198a001-0000-7000-8000-000000000014";

  private SingleConnectionDataSource dataSource;
  private JdbcFinalFixDeliveryStore store;
  private FinalMatchingEventFixDeliveryHandler handler;
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
    final JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
    store = new JdbcFinalFixDeliveryStore(jdbcTemplate);
    final OrderSessionRegistry registry = new OrderSessionRegistry();
    register(registry, IOC_ORDER_ID, TimeInForce.TIME_IN_FORCE_IOC, "IOC-1");
    register(registry, FOK_ORDER_ID, TimeInForce.TIME_IN_FORCE_FOK, "FOK-1");
    register(registry, ROD_ORDER_ID, TimeInForce.TIME_IN_FORCE_ROD, "ROD-1");
    register(registry, EXPIRED_ORDER_ID, TimeInForce.TIME_IN_FORCE_ROD, "ROD-2");
    handler =
        new FinalMatchingEventFixDeliveryApplicationService(
            store, new FinalMatchingEventFixDeliveryPlanner(registry), CLOCK);
    transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
  }

  @AfterEach
  void tearDown() {
    dataSource.destroy();
  }

  @Test
  void iocAndFokTerminalEventsPersistStableCanceledReportsWithExplicitReasons() throws Exception {
    final FinalMatchingEventEnvelope ioc =
        terminalEnvelope(
            IOC_ORDER_ID,
            0x0a,
            42L,
            MatchingEventType.MATCHING_EVENT_TYPE_ORDER_CANCELLED,
            CancellationReason.CANCELLATION_REASON_IOC_REMAINDER,
            40L);
    final FinalMatchingEventEnvelope fok =
        terminalEnvelope(
            FOK_ORDER_ID,
            0x0b,
            43L,
            MatchingEventType.MATCHING_EVENT_TYPE_ORDER_CANCELLED,
            CancellationReason.CANCELLATION_REASON_FOK_NOT_FILLED,
            100L);

    assertThat(persist(ioc)).isEqualTo(FinalMatchingEventFixDeliveryOutcome.APPLIED);
    assertThat(persist(fok)).isEqualTo(FinalMatchingEventFixDeliveryOutcome.APPLIED);

    final Map<UUID, FinalFixDeliveryIntent> intents = pendingByOrder();
    assertCanceled(
        intents.get(UUID.fromString(IOC_ORDER_ID)),
        CancellationReason.CANCELLATION_REASON_IOC_REMAINDER.name(),
        40L);
    assertCanceled(
        intents.get(UUID.fromString(FOK_ORDER_ID)),
        CancellationReason.CANCELLATION_REASON_FOK_NOT_FILLED.name(),
        100L);

    final String iocExecutionId =
        intents.get(UUID.fromString(IOC_ORDER_ID)).report().executionId();
    assertThat(persist(ioc)).isEqualTo(FinalMatchingEventFixDeliveryOutcome.DUPLICATE);
    assertThat(store.findPending(10)).hasSize(2);
    assertThat(pendingByOrder().get(UUID.fromString(IOC_ORDER_ID)).report().executionId())
        .isEqualTo(iocExecutionId);
  }

  @Test
  void restedAndExpiredEventsPersistExpectedFixLifecycleStates() throws Exception {
    assertThat(persist(restedEnvelope()))
        .isEqualTo(FinalMatchingEventFixDeliveryOutcome.APPLIED);
    assertThat(
            persist(
                terminalEnvelope(
                    EXPIRED_ORDER_ID,
                    0x0d,
                    45L,
                    MatchingEventType.MATCHING_EVENT_TYPE_ORDER_EXPIRED,
                    CancellationReason.CANCELLATION_REASON_SESSION_EXPIRED,
                    100L)))
        .isEqualTo(FinalMatchingEventFixDeliveryOutcome.APPLIED);

    final Map<UUID, FinalFixDeliveryIntent> intents = pendingByOrder();
    final FinalFixDeliveryIntent rested = intents.get(UUID.fromString(ROD_ORDER_ID));
    assertThat(rested.report().executionType()).isEqualTo('0');
    assertThat(rested.report().orderStatus()).isEqualTo('0');
    assertThat(rested.report().leavesQuantity()).isEqualTo(100L);

    final FinalFixDeliveryIntent expired = intents.get(UUID.fromString(EXPIRED_ORDER_ID));
    assertThat(expired.report().executionType()).isEqualTo('C');
    assertThat(expired.report().orderStatus()).isEqualTo('C');
    assertThat(expired.report().text())
        .isEqualTo(CancellationReason.CANCELLATION_REASON_SESSION_EXPIRED.name());
  }

  private void assertCanceled(FinalFixDeliveryIntent intent, String reason, long leavesQuantity) {
    assertThat(intent).isNotNull();
    assertThat(intent.report().executionType()).isEqualTo('4');
    assertThat(intent.report().orderStatus()).isEqualTo('4');
    assertThat(intent.report().lastQuantity()).isZero();
    assertThat(intent.report().leavesQuantity()).isEqualTo(leavesQuantity);
    assertThat(intent.report().text()).isEqualTo(reason);
  }

  private FinalMatchingEventFixDeliveryOutcome persist(FinalMatchingEventEnvelope envelope) {
    return transactions.execute(
        ignored -> handler.persist(envelope, 0, envelope.event().getSourceInputOffset()));
  }

  private Map<UUID, FinalFixDeliveryIntent> pendingByOrder() {
    return store.findPending(10).stream()
        .collect(Collectors.toMap(intent -> intent.recipient().orderId(), Function.identity()));
  }

  private void register(
      OrderSessionRegistry registry, String orderId, TimeInForce timeInForce, String clientOrderId) {
    registry.registerAcceptedOrder(
        new SessionID("FIX.4.4", "SIMPLEMATCH", "CLIENT"),
        new WalRecord(
            new WalMetadata("v1", UUID.randomUUID().toString(), 1L, "quickfix-gateway"),
            new FixSessionIdentity("CLIENT", "SIMPLEMATCH"),
            new WalOrderReference(orderId, clientOrderId, "", ACCOUNT_ID),
            new WalCommand.NewOrder(
                new WalOrderTerms(
                    "2330",
                    Side.SIDE_BUY,
                    "100",
                    "100",
                    OrderType.ORDER_TYPE_LIMIT,
                    timeInForce)),
            new RawFixMessage("raw")),
        'A');
  }

  private FinalMatchingEventEnvelope restedEnvelope() throws Exception {
    return FinalMatchingEventEnvelope.parse(
        eventBase(0x0c, 44L)
            .setEventType(MatchingEventType.MATCHING_EVENT_TYPE_ORDER_RESTED)
            .setOrderRested(
                OrderRested.newBuilder()
                    .setOrderId(ROD_ORDER_ID)
                    .setAccountId(ACCOUNT_ID)
                    .setInstrument(instrument())
                    .setSide(Side.SIDE_BUY)
                    .setRestingPriceUnits(1_000_000L)
                    .setLeavesQuantityShares(100L))
            .build()
            .toByteArray());
  }

  private FinalMatchingEventEnvelope terminalEnvelope(
      String orderId,
      int outputIndex,
      long sourceOffset,
      MatchingEventType eventType,
      CancellationReason reason,
      long leavesQuantity)
      throws Exception {
    final OrderTerminal terminal =
        OrderTerminal.newBuilder()
            .setOrderId(orderId)
            .setAccountId(ACCOUNT_ID)
            .setInstrument(instrument())
            .setSide(Side.SIDE_BUY)
            .setLeavesQuantityShares(leavesQuantity)
            .setReason(reason)
            .build();
    final MatchingEvent.Builder event = eventBase(outputIndex, sourceOffset).setEventType(eventType);
    if (eventType == MatchingEventType.MATCHING_EVENT_TYPE_ORDER_CANCELLED) {
      event.setOrderCancelled(terminal);
    } else {
      event.setOrderExpired(terminal);
    }
    return FinalMatchingEventEnvelope.parse(event.build().toByteArray());
  }

  private MatchingEvent.Builder eventBase(int outputIndex, long sourceOffset) {
    return MatchingEvent.newBuilder()
        .setSchemaVersion(1)
        .setIdentityVersion(1)
        .setEventId(
            ByteString.copyFrom(
                MatchingEventIdentityV1.eventId(
                    TRADING_SESSION_ID, PARTITION_ID, SOURCE_COMMAND_ID, outputIndex)))
        .setTradingSessionId(TRADING_SESSION_ID)
        .setPartitionId(PARTITION_ID)
        .setSourceCommandId(SOURCE_COMMAND_ID.toString())
        .setSourceInputOffset(sourceOffset)
        .setOutputIndex(outputIndex)
        .setArtifactIdentity(ARTIFACT)
        .setRoutingAlgorithmVersion("stable-least-loaded-v1");
  }

  private VenueInstrument instrument() {
    return VenueInstrument.newBuilder().setVenueMic("XTAI").setSymbol("2330").build();
  }
}
