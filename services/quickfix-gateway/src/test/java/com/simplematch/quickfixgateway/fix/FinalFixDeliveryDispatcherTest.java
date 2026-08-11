package com.simplematch.quickfixgateway.fix;

import static org.assertj.core.api.Assertions.assertThat;

import com.simplematch.contracts.common.v2.OrderType;
import com.simplematch.contracts.common.v2.Side;
import com.simplematch.contracts.common.v2.TimeInForce;
import com.simplematch.quickfixgateway.store.JdbcFinalFixDeliveryStore;
import com.simplematch.quickfixgateway.test.FixMessageSnapshot;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import quickfix.Message;
import quickfix.SessionID;

/** Verifies socket uncertainty leaves the durable report intent pending for same-ExecID replay. */
class FinalFixDeliveryDispatcherTest {
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-08-11T00:00:00Z"), ZoneOffset.UTC);
  private static final UUID ORDER_ID = UUID.fromString("0198a001-0000-7000-8000-000000000011");

  private SingleConnectionDataSource dataSource;
  private JdbcFinalFixDeliveryStore store;
  private OrderSessionRegistry registry;

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
    store =
        new JdbcFinalFixDeliveryStore(new org.springframework.jdbc.core.JdbcTemplate(dataSource));
    registry = new OrderSessionRegistry();
    registry.registerAcceptedOrder(
        sessionId(),
        new WalRecord(
            new WalMetadata("v1", UUID.randomUUID().toString(), 1L, "quickfix-gateway"),
            new FixSessionIdentity("CLIENT", "SIMPLEMATCH"),
            new WalOrderReference(
                ORDER_ID.toString(), "C-1", "", "0198a001-0000-7000-8000-0000000000aa"),
            new WalCommand.NewOrder(
                new WalOrderTerms(
                    "2330",
                    Side.SIDE_BUY,
                    "100",
                    "100",
                    OrderType.ORDER_TYPE_LIMIT,
                    TimeInForce.TIME_IN_FORCE_ROD)),
            new RawFixMessage("raw")),
        'A');
  }

  @AfterEach
  void tearDown() {
    dataSource.destroy();
  }

  @Test
  void failedSendRemainsPendingAndReplayUsesTheSameStableExecId() {
    store.recordDeliveryIntents(List.of(intent()));
    final List<Message> sent = new ArrayList<>();
    final int[] attempts = {0};
    final FixSessionMessageSender sender =
        (sessionId, message) -> {
          attempts[0] += 1;
          if (attempts[0] == 1) {
            throw new IllegalStateException("socket outcome is uncertain");
          }
          sent.add(message);
        };
    final FinalFixDeliveryDispatcher dispatcher =
        new FinalFixDeliveryDispatcher(
            store, new FinalFixExecutionReportMapper(), sender, registry, CLOCK, 10);

    dispatcher.dispatchPending();
    assertThat(store.pendingIntentCount()).isEqualTo(1L);

    dispatcher.dispatchPending();

    assertThat(store.pendingIntentCount()).isZero();
    assertThat(attempts[0]).isEqualTo(2);
    assertThat(FixMessageSnapshot.snapshot(sent.getFirst(), 35, 17, 150, 39, 151, 14, 6, 32, 31))
        .isEqualTo("35=8|17=trade-id-maker|150=2|39=2|151=0|14=100|6=100|32=100|31=100");
    assertThat(registry.find(ORDER_ID.toString()).orElseThrow().lifecycle().currentOrdStatus())
        .isEqualTo('2');
  }

  private FinalFixDeliveryIntent intent() {
    final FixOrderSnapshot order =
        new FixOrderSnapshot(
            new FixOrderSnapshot.OrderId(ORDER_ID.toString()),
            new FixOrderSnapshot.ClientOrderId("C-1"),
            new FixOrderSnapshot.Symbol("2330"),
            Side.SIDE_BUY,
            new FixOrderSnapshot.Quantity("100"));
    return new FinalFixDeliveryIntent(
        new FinalFixDeliveryIdentity("d".repeat(64), "e".repeat(64), 0),
        new FinalFixDeliveryRecipient(ORDER_ID, sessionId(), order),
        new FinalFixDeliveryReport(
            "trade-id-maker", '2', '2', 100, 1_000_000, 100, 0, 1_000_000, ""),
        0,
        42L,
        CLOCK.millis());
  }

  private SessionID sessionId() {
    return new SessionID("FIX.4.4", "SIMPLEMATCH", "CLIENT");
  }
}
