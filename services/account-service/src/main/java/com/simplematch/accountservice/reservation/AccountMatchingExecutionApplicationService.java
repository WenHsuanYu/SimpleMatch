package com.simplematch.accountservice.reservation;

import com.simplematch.accountservice.authority.AccountAuthorityReader;
import com.simplematch.accountservice.authority.AccountReservation;
import com.simplematch.accountservice.kafka.AccountLifecycleApplier;
import com.simplematch.contracts.matching.v1.ExecutionEvent;
import java.math.BigDecimal;
import java.util.Objects;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Applies matching execution facts to Account Authority reservation use cases. */
@Service
@RequiredArgsConstructor
public class AccountMatchingExecutionApplicationService
    implements AccountLifecycleApplier, AccountMatchingExecutionHandler {
  private static final int TRANSACTION_TIMEOUT_SECONDS = 8;

  @NonNull private final AccountAuthorityReader authorityReader;
  @NonNull private final AccountReservationApplicationService reservationService;

  /** Applies the retained legacy execution contract through the Account transaction boundary. */
  @Transactional(timeout = TRANSACTION_TIMEOUT_SECONDS)
  @Override
  public ReservationRecord applyMatchingExecution(ExecutionEvent event) {
    Objects.requireNonNull(event, "execution event");
    validateIdentity(event);
    final AccountReservation reservation =
        findReservation(event.getOrderId(), event.getAccountId(), event.getSymbol());
    final ReservationIdentity identity = reservationIdentity(reservation);
    return switch (event.getExecutionType()) {
      case EXECUTION_TYPE_PARTIAL_FILL, EXECUTION_TYPE_FILL ->
          applyFill(
              identity,
              new MatchingAccountEffect.Fill(
                  event.getExecId(),
                  event.getOrderId(),
                  event.getAccountId(),
                  event.getSymbol(),
                  new ExecutionFill.FillQuantity(parsePositive(event.getFillQty(), "fill_qty")),
                  new ExecutionFill.FillPrice(parsePositive(event.getFillPx(), "fill_px"))));
      case EXECUTION_TYPE_CANCELED, EXECUTION_TYPE_REJECTED ->
          applyTerminal(
              identity,
              new MatchingAccountEffect.Terminal(
                  event.getExecId(),
                  event.getOrderId(),
                  event.getAccountId(),
                  event.getSymbol(),
                  new ReleaseReservationOperation.ReleaseReason(
                      event.getText().isBlank() ? "MATCHING_TERMINAL" : event.getText())));
      default -> ReservationRecord.from(reservation);
    };
  }

  /** Applies an Account-owned matching effect without exposing its wire representation. */
  @Override
  @Transactional(timeout = TRANSACTION_TIMEOUT_SECONDS)
  public ReservationRecord apply(MatchingAccountEffect effect) {
    final MatchingAccountEffect command = Objects.requireNonNull(effect, "effect");
    final AccountReservation reservation =
        findReservation(command.orderId(), command.accountId(), command.symbol());
    final ReservationIdentity identity = reservationIdentity(reservation);
    return switch (command) {
      case MatchingAccountEffect.Fill fill -> applyFill(identity, fill);
      case MatchingAccountEffect.Terminal terminal -> applyTerminal(identity, terminal);
    };
  }

  private ReservationRecord applyFill(
      ReservationIdentity identity, MatchingAccountEffect.Fill fill) {
    return reservationService.applyFill(
        new ApplyFillOperation(
            identity,
            new ExecutionFill(
                new ExecutionFill.ExecutionId(fill.executionId()),
                ExecutionFill.AggregateSequence.absent(),
                fill.quantity(),
                fill.price())));
  }

  private ReservationRecord applyTerminal(
      ReservationIdentity identity, MatchingAccountEffect.Terminal terminal) {
    return reservationService.release(
        new ReleaseReservationOperation(identity, terminal.reason(), terminal.executionId()));
  }

  private AccountReservation findReservation(String orderId, String accountId, String symbol) {
    final AccountReservation reservation =
        authorityReader
            .findReservationByOrderId(orderId)
            .orElseThrow(() -> new IllegalArgumentException("reservation not found for order"));
    if (!reservation.accountId().equals(accountId) || !reservation.symbol().equals(symbol)) {
      throw new IllegalArgumentException("matching execution reservation identity does not match");
    }
    return reservation;
  }

  private ReservationIdentity reservationIdentity(AccountReservation reservation) {
    return new ReservationIdentity(
        new ReservationIdentity.RequestId(reservation.requestId()),
        new ReservationIdentity.ReservationId(reservation.reservationId()),
        new ReservationIdentity.OrderId(reservation.orderId()));
  }

  private void validateIdentity(ExecutionEvent event) {
    if (event.getOrderId().isBlank() || event.getAccountId().isBlank()) {
      throw new IllegalArgumentException("matching execution identity is incomplete");
    }
  }

  private BigDecimal parsePositive(String value, String fieldName) {
    try {
      final BigDecimal parsed = new BigDecimal(value);
      if (parsed.signum() <= 0) {
        throw new NumberFormatException("not positive");
      }
      return parsed;
    } catch (NumberFormatException exception) {
      throw new IllegalArgumentException(fieldName + " must be positive", exception);
    }
  }
}
