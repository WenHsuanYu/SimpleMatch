package com.simplematch.quickfixgateway.fix;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.ByteString;
import com.simplematch.contracts.common.v2.Side;
import com.simplematch.contracts.common.v2.VenueInstrument;
import com.simplematch.contracts.matching.runtime.v1.ArtifactIdentity;
import com.simplematch.contracts.matching.runtime.v1.FinalMatchingEventEnvelope;
import com.simplematch.contracts.matching.runtime.v1.MatchingEvent;
import com.simplematch.contracts.matching.runtime.v1.MatchingEventIdentityV1;
import com.simplematch.contracts.matching.runtime.v1.MatchingEventType;
import com.simplematch.contracts.matching.runtime.v1.OrderRested;
import com.simplematch.contracts.orders.v2.CancelOrderCommand;
import com.simplematch.contracts.orders.v2.NewOrderCommand;
import com.simplematch.quickfixgateway.matching.FinalMatchingEventFixDeliveryApplicationService;
import com.simplematch.quickfixgateway.matching.FinalMatchingEventFixDeliveryHandler;
import com.simplematch.quickfixgateway.matching.FinalMatchingEventFixDeliveryOutcome;
import com.simplematch.quickfixgateway.matching.FinalMatchingEventFixDeliveryPlanner;
import com.simplematch.quickfixgateway.risk.RiskOrderIdentityDeriver;
import com.simplematch.quickfixgateway.risk.RiskSubmissionClient;
import com.simplematch.quickfixgateway.risk.RiskSubmissionResult;
import com.simplematch.quickfixgateway.store.JdbcFinalFixDeliveryStore;
import com.simplematch.quickfixgateway.wal.WalAppender;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import quickfix.Message;
import quickfix.SessionID;
import quickfix.field.Account;
import quickfix.field.ClOrdID;
import quickfix.field.HandlInst;
import quickfix.field.OrdType;
import quickfix.field.OrderQty;
import quickfix.field.Price;
import quickfix.field.Symbol;
import quickfix.field.TransactTime;
import quickfix.fix44.NewOrderSingle;

/** Verifies accepted FIX orders remain addressable by final Matching Event order identity. */
class FinalMatchingEventFixAdmissionIntegrationTest {
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-08-28T00:00:00Z"), ZoneOffset.UTC);
  private static final LocalDate TRADING_DAY = LocalDate.of(2026, 8, 27);
  private static final String ACCOUNT_ID = "0198a001-0000-7000-8000-0000000000aa";
  private static final String CLIENT_ORDER_ID = "FINAL-1";
  private static final SessionID SESSION_ID =
      new SessionID("FIX.4.4", "SIMPLEMATCH", "CLIENT");
  private static final ArtifactIdentity ARTIFACT =
      ArtifactIdentity.newBuilder()
          .setTradingDay(TRADING_DAY.toString())
          .setContentSha256("7cd06c51691bcde248e606ed1adfaddc4bd10ece582a6803fd2f04155a032943")
          .build();
  private static final String TRADING_SESSION_ID = "2026-08-27-regular";
  private static final int PARTITION_ID = 0;
  private static final UUID SOURCE_COMMAND_ID =
      UUID.fromString("0198a001-0000-7000-8000-000000000001");
  private static final int OUTPUT_INDEX = 0;

  @TempDir Path tempDir;

  private SingleConnectionDataSource dataSource;
  private JdbcFinalFixDeliveryStore store;
  private TransactionTemplate transactions;
  private WalAppender walAppender;

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
    store = new JdbcFinalFixDeliveryStore(new JdbcTemplate(dataSource));
    transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    walAppender =
        new WalAppender(tempDir.resolve("wal").resolve("inbound.wal"), StandardCharsets.UTF_8);
  }

  @AfterEach
  void tearDown() throws Exception {
    walAppender.close();
    dataSource.destroy();
  }

  @Test
  void acceptedRiskOrderIdentityResolvesTheOwningFixSessionForFinalEvents() throws Exception {
    final RiskOrderIdentityDeriver orderIdentityDeriver =
        new RiskOrderIdentityDeriver(TRADING_DAY);
    final OrderSessionRegistry registry = new OrderSessionRegistry(orderIdentityDeriver);
    final CapturingAcceptedRiskClient risk = new CapturingAcceptedRiskClient();
    final List<Message> admissionMessages = new ArrayList<>();
    final InboundFixMessageHandler inbound =
        QuickFixIngressTestFixture.compose(
            walAppender,
            risk,
            (sessionId, message) -> admissionMessages.add(message),
            registry,
            new FixMessageMapper(CLOCK),
            CLOCK,
            orderIdentityDeriver);

    inbound.handle(newOrder(), SESSION_ID);

    assertThat(risk.acceptedOrderId).isNotNull();
    assertThat(admissionMessages).hasSize(1);

    final FinalMatchingEventFixDeliveryHandler finalEvents =
        new FinalMatchingEventFixDeliveryApplicationService(
            store, new FinalMatchingEventFixDeliveryPlanner(registry), CLOCK);
    final FinalMatchingEventEnvelope rested = restedEnvelope(risk.acceptedOrderId);
    final FinalMatchingEventFixDeliveryOutcome outcome =
        transactions.execute(ignored -> finalEvents.persist(rested, 0, 42L));

    assertThat(outcome).isEqualTo(FinalMatchingEventFixDeliveryOutcome.APPLIED);
    assertThat(store.findPending(10))
        .singleElement()
        .satisfies(
            intent -> {
              assertThat(intent.recipient().sessionId()).isEqualTo(SESSION_ID);
              assertThat(intent.recipient().order().clientOrderId().value())
                  .isEqualTo(CLIENT_ORDER_ID);
              assertThat(intent.recipient().orderId().toString())
                  .isEqualTo(risk.acceptedOrderId);
            });
  }

  private NewOrderSingle newOrder() {
    final NewOrderSingle order = new NewOrderSingle();
    order.setString(ClOrdID.FIELD, CLIENT_ORDER_ID);
    order.setString(Symbol.FIELD, "2330");
    order.setChar(quickfix.field.Side.FIELD, '1');
    order.setString(OrderQty.FIELD, "100");
    order.setChar(OrdType.FIELD, '2');
    order.setString(Price.FIELD, "100");
    order.setChar(HandlInst.FIELD, '1');
    order.setString(TransactTime.FIELD, "20260828-00:00:00.000");
    order.setString(Account.FIELD, ACCOUNT_ID);
    return order;
  }

  private FinalMatchingEventEnvelope restedEnvelope(String orderId) throws Exception {
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
            .setEventType(MatchingEventType.MATCHING_EVENT_TYPE_ORDER_RESTED)
            .setOrderRested(
                OrderRested.newBuilder()
                    .setOrderId(orderId)
                    .setAccountId(ACCOUNT_ID)
                    .setInstrument(
                        VenueInstrument.newBuilder().setVenueMic("XTAI").setSymbol("2330"))
                    .setSide(Side.SIDE_BUY)
                    .setLeavesQuantityShares(100L)
                    .setRestingPriceUnits(1_000_000L))
            .build()
            .toByteArray());
  }

  private static final class CapturingAcceptedRiskClient implements RiskSubmissionClient {
    private String acceptedOrderId;

    @Override
    public RiskSubmissionResult submitNewOrder(NewOrderCommand command) {
      acceptedOrderId = command.getOrderId();
      return new RiskSubmissionResult(acceptedOrderId, true, "", "");
    }

    @Override
    public RiskSubmissionResult submitCancel(CancelOrderCommand command) {
      return new RiskSubmissionResult(command.getOrderId(), true, "", "");
    }
  }
}
