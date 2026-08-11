package com.simplematch.accountservice.matching;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.simplematch.accountservice.kafka.AccountLifecycleApplier;
import com.simplematch.accountservice.reservation.ReservationRecord;
import com.simplematch.accountservice.store.JdbcFinalMatchingEventAccountInbox;
import com.simplematch.contracts.common.v2.Side;
import com.simplematch.contracts.common.v2.VenueInstrument;
import com.simplematch.contracts.matching.runtime.v1.ArtifactIdentity;
import com.simplematch.contracts.matching.runtime.v1.DeterministicEventConflictException;
import com.simplematch.contracts.matching.runtime.v1.FinalMatchingEventEnvelope;
import com.simplematch.contracts.matching.runtime.v1.MatchingEvent;
import com.simplematch.contracts.matching.runtime.v1.MatchingEventType;
import com.simplematch.contracts.matching.runtime.v1.OrderTerminal;
import com.simplematch.contracts.matching.runtime.v1.TradeExecuted;
import com.simplematch.contracts.matching.runtime.v1.TradeLeg;
import com.simplematch.contracts.matching.v1.ExecutionEvent;
import com.simplematch.contracts.matching.v1.ExecutionType;
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

/** Verifies the final-event adapter keeps account authority effects in one local transaction. */
class FinalMatchingEventAccountApplicationServiceTest {
  private static final ArtifactIdentity ARTIFACT =
      ArtifactIdentity.newBuilder()
          .setTradingDay("2026-08-11")
          .setContentSha256("7cd06c51691bcde248e606ed1adfaddc4bd10ece582a6803fd2f04155a032943")
          .build();
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-08-11T00:00:00Z"), ZoneOffset.UTC);
  private static final String EVENT_ID = "e".repeat(64);

  private SingleConnectionDataSource dataSource;
  private JdbcTemplate jdbcTemplate;
  private JdbcFinalMatchingEventAccountInbox inbox;

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
        .locations("classpath:db/migration/account-service")
        .load()
        .migrate();
    jdbcTemplate = new JdbcTemplate(dataSource);
    inbox = new JdbcFinalMatchingEventAccountInbox(jdbcTemplate);
  }

  @AfterEach
  void tearDown() {
    dataSource.destroy();
  }

  @Test
  void mapsBothTradeLegsWithIndependentTradeAndAveragePrices() throws Exception {
    final List<ExecutionEvent> applied = new ArrayList<>();
    final FinalMatchingEventAccountApplicationService service = service(applied::add);

    assertThat(service.apply(tradeEnvelope(EVENT_ID, 1_000_000L), 0, 42L))
        .isEqualTo(FinalMatchingEventAccountOutcome.APPLIED);

    assertThat(applied)
        .extracting(ExecutionEvent::getOrderId, ExecutionEvent::getExecutionType)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(
                "0198a001-0000-7000-8000-000000000011", ExecutionType.EXECUTION_TYPE_FILL),
            org.assertj.core.groups.Tuple.tuple(
                "0198a001-0000-7000-8000-000000000012", ExecutionType.EXECUTION_TYPE_PARTIAL_FILL));
    assertThat(applied)
        .allSatisfy(
            event -> {
              assertThat(event.getFillPx()).isEqualTo("100.0000");
              assertThat(event.getAveragePx()).isEqualTo("90.0000");
            });
    assertThat(count("matching_event_inbox")).isEqualTo(1);
    assertThat(count("matching_event_consumer_progress")).isEqualTo(1);
  }

  @Test
  void deduplicatesOneReplayedRawEventBeforeItReachesAccountAuthority() throws Exception {
    final List<ExecutionEvent> applied = new ArrayList<>();
    final FinalMatchingEventAccountApplicationService service = service(applied::add);
    final FinalMatchingEventEnvelope envelope = tradeEnvelope(EVENT_ID, 1_000_000L);

    assertThat(service.apply(envelope, 0, 42L)).isEqualTo(FinalMatchingEventAccountOutcome.APPLIED);
    assertThat(service.apply(envelope, 0, 42L))
        .isEqualTo(FinalMatchingEventAccountOutcome.DUPLICATE);

    assertThat(applied).hasSize(2);
  }

  @Test
  void failsClosedForConflictingBytesWithTheSameFinalEventIdentity() throws Exception {
    final FinalMatchingEventAccountApplicationService service = service(ignored -> {});

    assertThat(service.apply(tradeEnvelope(EVENT_ID, 1_000_000L), 0, 42L))
        .isEqualTo(FinalMatchingEventAccountOutcome.APPLIED);

    assertThatThrownBy(() -> service.apply(tradeEnvelope(EVENT_ID, 1_000_001L), 0, 42L))
        .isInstanceOf(DeterministicEventConflictException.class);
  }

  @Test
  void rollsBackItsRawInboxWhenOneAccountAuthorityEffectFails() throws Exception {
    final FinalMatchingEventAccountApplicationService service =
        service(
            event -> {
              throw new IllegalStateException("account authority is unavailable");
            });
    final TransactionTemplate transactions =
        new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    final FinalMatchingEventEnvelope envelope = tradeEnvelope(EVENT_ID, 1_000_000L);

    assertThatThrownBy(
            () -> transactions.executeWithoutResult(ignored -> service.apply(envelope, 0, 42L)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("account authority is unavailable");

    assertThat(count("matching_event_inbox")).isZero();
    assertThat(count("matching_event_consumer_progress")).isZero();
  }

  @Test
  void mapsExpiredOrdersToAnAccountCancellationWithoutAFill() throws Exception {
    final List<ExecutionEvent> applied = new ArrayList<>();
    final FinalMatchingEventAccountApplicationService service = service(applied::add);

    assertThat(service.apply(expiredEnvelope(), 0, 43L))
        .isEqualTo(FinalMatchingEventAccountOutcome.APPLIED);

    assertThat(applied)
        .singleElement()
        .satisfies(
            event -> {
              assertThat(event.getExecutionType()).isEqualTo(ExecutionType.EXECUTION_TYPE_CANCELED);
              assertThat(event.getFillQty()).isBlank();
              assertThat(event.getText()).contains("MATCHING_EXPIRED");
            });
  }

  private FinalMatchingEventAccountApplicationService service(EventSink sink) {
    final AccountLifecycleApplier applier =
        event -> {
          sink.accept(event);
          return (ReservationRecord) null;
        };
    return new FinalMatchingEventAccountApplicationService(inbox, applier, CLOCK);
  }

  private int count(String table) {
    return jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM account_service." + table, Integer.class);
  }

  private FinalMatchingEventEnvelope tradeEnvelope(String eventId, long priceUnits)
      throws Exception {
    return FinalMatchingEventEnvelope.parse(
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
                    .setTradeId("d".repeat(64))
                    .setInstrument(
                        VenueInstrument.newBuilder().setVenueMic("XTAI").setSymbol("2330"))
                    .setMaker(
                        leg("0198a001-0000-7000-8000-000000000011", Side.SIDE_SELL, priceUnits, 0L))
                    .setTaker(
                        leg(
                            "0198a001-0000-7000-8000-000000000012",
                            Side.SIDE_BUY,
                            priceUnits,
                            200L)))
            .build()
            .toByteArray());
  }

  private FinalMatchingEventEnvelope expiredEnvelope() throws Exception {
    return FinalMatchingEventEnvelope.parse(
        MatchingEvent.newBuilder()
            .setSchemaVersion(1)
            .setIdentityVersion(1)
            .setEventId("f".repeat(64))
            .setTradingSessionId("2026-08-11-regular")
            .setPartitionId(0)
            .setSourceCommandId("0198a001-0000-7000-8000-000000000001")
            .setSourceInputOffset(43L)
            .setOutputIndex(0)
            .setArtifactIdentity(ARTIFACT)
            .setRoutingAlgorithmVersion("stable-least-loaded-v1")
            .setEventType(MatchingEventType.MATCHING_EVENT_TYPE_ORDER_EXPIRED)
            .setOrderExpired(
                OrderTerminal.newBuilder()
                    .setOrderId("0198a001-0000-7000-8000-000000000011")
                    .setAccountId("0198a001-0000-7000-8000-0000000000aa")
                    .setInstrument(
                        VenueInstrument.newBuilder().setVenueMic("XTAI").setSymbol("2330"))
                    .setSide(Side.SIDE_SELL)
                    .setLeavesQuantityShares(100L)
                    .setReason(
                        com.simplematch.contracts.matching.runtime.v1.CancellationReason
                            .CANCELLATION_REASON_SESSION_EXPIRED))
            .build()
            .toByteArray());
  }

  private TradeLeg leg(String orderId, Side side, long priceUnits, long leavesQuantityShares) {
    return TradeLeg.newBuilder()
        .setOrderId(orderId)
        .setAccountId("0198a001-0000-7000-8000-0000000000aa")
        .setSide(side)
        .setQuantityShares(100L)
        .setPriceUnits(priceUnits)
        .setAveragePriceUnits(priceUnits - 100_000L)
        .setCumulativeQuantityShares(100L)
        .setLeavesQuantityShares(leavesQuantityShares)
        .build();
  }

  @FunctionalInterface
  private interface EventSink {
    void accept(ExecutionEvent event);
  }
}
