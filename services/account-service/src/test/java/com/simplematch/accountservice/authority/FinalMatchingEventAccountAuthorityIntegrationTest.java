package com.simplematch.accountservice.authority;

import static org.assertj.core.api.Assertions.assertThat;

import com.simplematch.accountservice.matching.FinalMatchingEventAccountApplicationService;
import com.simplematch.accountservice.matching.FinalMatchingEventAccountCommand;
import com.simplematch.accountservice.matching.FinalMatchingEventAccountHandler;
import com.simplematch.accountservice.matching.FinalMatchingEventAccountOutcome;
import com.simplematch.accountservice.reservation.AccountReservationApplicationService;
import com.simplematch.accountservice.reservation.ExecutionFill;
import com.simplematch.accountservice.reservation.FinalMatchingAccountEffectApplicationService;
import com.simplematch.accountservice.reservation.MatchingAccountEffect;
import com.simplematch.accountservice.reservation.ReleaseReservationOperation;
import com.simplematch.accountservice.reservation.ReservationIdentity;
import com.simplematch.accountservice.reservation.ReservationRecord;
import com.simplematch.accountservice.reservation.ReservationRequestIdentity;
import com.simplematch.accountservice.reservation.ReservationTerms;
import com.simplematch.accountservice.reservation.ReserveOperation;
import com.simplematch.accountservice.store.JdbcFinalMatchingEventAccountInbox;
import com.simplematch.contracts.common.v1.ReservationStatus;
import com.simplematch.contracts.common.v1.Side;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Verifies final Matching effects through the real Account Authority application Interface. */
@SpringJUnitConfig(AccountReservationApplicationServiceTransactionTest.TestConfiguration.class)
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class FinalMatchingEventAccountAuthorityIntegrationTest {
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-07-28T01:00:00Z"), ZoneOffset.UTC);
  private static final LocalDate TRADING_DAY = LocalDate.of(2026, 7, 28);
  private static final String BUYER_ACCOUNT_ID = "0194a8f0-7c77-7b38-9e2d-2a5fdd0f7c13";
  private static final String SELLER_ACCOUNT_ID = "0194a8f0-7c77-7b38-9e2d-2a5fdd0f7c14";
  private static final String MAKER_EXECUTION_ID = "00000000-0000-0000-0000-000000000101";
  private static final String TAKER_EXECUTION_ID = "00000000-0000-0000-0000-000000000102";
  private static final String IOC_TERMINAL_ID = "00000000-0000-0000-0000-000000000103";
  private static final String FOK_TERMINAL_ID = "00000000-0000-0000-0000-000000000104";

  private final JdbcTemplate jdbcTemplate;
  private final AccountReservationApplicationService reservationService;
  private final FinalMatchingEventAccountHandler handler;
  private final TransactionTemplate transactions;

  FinalMatchingEventAccountAuthorityIntegrationTest(
      JdbcTemplate jdbcTemplate,
      AccountAuthorityReader authorityReader,
      AccountReservationApplicationService reservationService,
      PlatformTransactionManager transactionManager) {
    this.jdbcTemplate = jdbcTemplate;
    this.reservationService = reservationService;
    this.handler =
        new FinalMatchingEventAccountApplicationService(
            new JdbcFinalMatchingEventAccountInbox(jdbcTemplate),
            new FinalMatchingAccountEffectApplicationService(authorityReader, reservationService),
            CLOCK);
    this.transactions = new TransactionTemplate(transactionManager);
  }

  @BeforeEach
  void reset() {
    jdbcTemplate.update("DELETE FROM account_service.matching_event_consumer_quarantines");
    jdbcTemplate.update("DELETE FROM account_service.matching_event_consumer_progress");
    jdbcTemplate.update("DELETE FROM account_service.matching_event_inbox");
    jdbcTemplate.update("DELETE FROM account_service.inbox");
    jdbcTemplate.update("DELETE FROM account_service.reservation_request_locks");
    jdbcTemplate.update("DELETE FROM account_service.outbox");
    jdbcTemplate.update("DELETE FROM account_service.account_reservations");
    jdbcTemplate.update("DELETE FROM account_service.account_positions");
    jdbcTemplate.update("DELETE FROM account_service.account_limits");

    provision(BUYER_ACCOUNT_ID, 0L);
    provision(SELLER_ACCOUNT_ID, 100L);
  }

  @Test
  void makerAndTakerFillsThenIocTerminalReleaseOnlyUnusedBuyerAuthority() {
    final ReservationRecord seller = reserve(SELLER_ACCOUNT_ID, Side.SIDE_SELL, "10", "100");
    final ReservationRecord buyer = reserve(BUYER_ACCOUNT_ID, Side.SIDE_BUY, "10", "100");

    assertThat(
            apply(
                command(
                    1,
                    11,
                    List.of(
                        fill(
                            seller,
                            MAKER_EXECUTION_ID,
                            "4",
                            "99",
                            MatchingAccountEffect.ResultingState.PARTIALLY_FILLED),
                        fill(
                            buyer,
                            TAKER_EXECUTION_ID,
                            "4",
                            "99",
                            MatchingAccountEffect.ResultingState.PARTIALLY_FILLED)))))
        .isEqualTo(FinalMatchingEventAccountOutcome.APPLIED);

    assertReservation(seller.orderId(), ReservationStatus.RESERVATION_STATUS_ACCEPTED, "4", "6");
    assertReservation(buyer.orderId(), ReservationStatus.RESERVATION_STATUS_ACCEPTED, "4", "6");

    final FinalMatchingEventAccountCommand iocRemainder =
        command(2, 12, List.of(terminal(buyer, IOC_TERMINAL_ID, "IOC_REMAINDER")));
    assertThat(apply(iocRemainder)).isEqualTo(FinalMatchingEventAccountOutcome.APPLIED);

    assertReservation(buyer.orderId(), ReservationStatus.RESERVATION_STATUS_RELEASED, "4", "0");
    assertReservation(seller.orderId(), ReservationStatus.RESERVATION_STATUS_ACCEPTED, "4", "6");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT reserved_notional FROM account_service.account_limits WHERE account_id = ?",
                BigDecimal.class,
                UUID.fromString(BUYER_ACCOUNT_ID)))
        .isEqualByComparingTo("0");

    final int outboxCountBeforeReplay = count("outbox");
    assertThat(apply(iocRemainder)).isEqualTo(FinalMatchingEventAccountOutcome.DUPLICATE);
    assertThat(count("outbox")).isEqualTo(outboxCountBeforeReplay);
  }

  @Test
  void fokTerminalReleaseReturnsEntireReservationWithoutCreatingFillAuthority() {
    final ReservationRecord buyer = reserve(BUYER_ACCOUNT_ID, Side.SIDE_BUY, "3", "100");

    assertThat(apply(command(3, 13, List.of(terminal(buyer, FOK_TERMINAL_ID, "FOK_NOT_FILLED")))))
        .isEqualTo(FinalMatchingEventAccountOutcome.APPLIED);

    assertReservation(buyer.orderId(), ReservationStatus.RESERVATION_STATUS_RELEASED, "0", "0");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT utilized_notional FROM account_service.account_limits WHERE account_id = ?",
                BigDecimal.class,
                UUID.fromString(BUYER_ACCOUNT_ID)))
        .isEqualByComparingTo("0");
  }

  private MatchingAccountEffect.Fill fill(
      ReservationRecord reservation,
      String executionId,
      String quantity,
      String price,
      MatchingAccountEffect.ResultingState resultingState) {
    return new MatchingAccountEffect.Fill(
        new ExecutionFill.ExecutionId(executionId),
        new ReservationIdentity.OrderId(reservation.orderId()),
        AccountId.parse(reservation.accountId()),
        new ReservationTerms.InstrumentSymbol(reservation.symbol()),
        new ExecutionFill.FillQuantity(new BigDecimal(quantity)),
        new ExecutionFill.FillPrice(new BigDecimal(price)),
        resultingState);
  }

  private MatchingAccountEffect.Terminal terminal(
      ReservationRecord reservation, String executionId, String reason) {
    return new MatchingAccountEffect.Terminal(
        new ExecutionFill.ExecutionId(executionId),
        new ReservationIdentity.OrderId(reservation.orderId()),
        AccountId.parse(reservation.accountId()),
        new ReservationTerms.InstrumentSymbol(reservation.symbol()),
        new ReleaseReservationOperation.ReleaseReason(reason));
  }

  private FinalMatchingEventAccountCommand command(
      int eventIdentityByte, int fingerprintByte, List<MatchingAccountEffect> effects) {
    return new FinalMatchingEventAccountCommand(
        new FinalMatchingEventAccountCommand.EventId(bytes(eventIdentityByte)),
        new FinalMatchingEventAccountCommand.PayloadFingerprint(bytes(fingerprintByte)),
        effects);
  }

  private FinalMatchingEventAccountOutcome apply(FinalMatchingEventAccountCommand command) {
    return transactions.execute(ignored -> handler.apply(command, 0, 42L));
  }

  private void provision(String accountId, long longQuantity) {
    reservationService.provisionLimit(accountId, TRADING_DAY, new BigDecimal("10000"));
    reservationService.provisionPosition(accountId, "2330");
    jdbcTemplate.update(
        "UPDATE account_service.account_positions SET long_qty = ? WHERE account_id = ? AND symbol = ?",
        BigDecimal.valueOf(longQuantity),
        UUID.fromString(accountId),
        "2330");
  }

  private ReservationRecord reserve(String accountId, Side side, String quantity, String price) {
    return reservationService.reserve(
        new ReserveOperation(
            new ReservationRequestIdentity(
                new ReservationRequestIdentity.RequestId(UUID.randomUUID().toString()),
                new ReservationRequestIdentity.OrderId(UUID.randomUUID().toString()),
                new ReservationRequestIdentity.AccountId(accountId)),
            new ReservationTerms(
                new ReservationTerms.InstrumentSymbol("2330"),
                side,
                new ReservationTerms.ReservationQuantity(new BigDecimal(quantity)),
                new ReservationTerms.LimitPrice(new BigDecimal(price)))));
  }

  private void assertReservation(
      String orderId, ReservationStatus status, String filledQuantity, String remainingQuantity) {
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT status FROM account_service.account_reservations WHERE order_id = ?",
                String.class,
                UUID.fromString(orderId)))
        .isEqualTo(status.name());
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT filled_quantity FROM account_service.account_reservations WHERE order_id = ?",
                BigDecimal.class,
                UUID.fromString(orderId)))
        .isEqualByComparingTo(filledQuantity);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT remaining_quantity FROM account_service.account_reservations WHERE order_id = ?",
                BigDecimal.class,
                UUID.fromString(orderId)))
        .isEqualByComparingTo(remainingQuantity);
  }

  private int count(String table) {
    return jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM account_service." + table, Integer.class);
  }

  private static byte[] bytes(int value) {
    final byte[] result = new byte[32];
    Arrays.fill(result, (byte) value);
    return result;
  }
}
