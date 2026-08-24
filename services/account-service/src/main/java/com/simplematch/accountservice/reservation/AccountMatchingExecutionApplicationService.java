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

/** Adapts retained matching execution events to Account Authority reservation use cases. */
@Service
@RequiredArgsConstructor
public class AccountMatchingExecutionApplicationService implements AccountLifecycleApplier {
  private static final int TRANSACTION_TIMEOUT_SECONDS = 8;

  @NonNull private final AccountAuthorityReader authorityReader;
  @NonNull private final AccountReservationApplicationService reservationService;

  /** Applies one retained matching fill or terminal outcome through the Account transaction. */
  @Transactional(timeout = TRANSACTION_TIMEOUT_SECONDS)
  @Override
  public ReservationRecord applyMatchingExecution(ExecutionEvent event) {
    Objects.requireNonNull(event, "execution event");
    validateIdentity(event);
    final AccountReservation reservation = findReservation(event);
    final ReservationIdentity identity = reservationIdentity(reservation);
    return transition(event, identity, reservation);
  }

  private AccountReservation findReservation(ExecutionEvent event) {
    final AccountReservation reservation =
        authorityReader
            .findReservationByOrderId(event.getOrderId())
            .orElseThrow(() -> new IllegalArgumentException("reservation not found for order"));
    if (!reservation.accountId().equals(event.getAccountId())
        || !reservation.symbol().equals(event.getSymbol())) {
      throw new IllegalArgumentException("matching execution reservation identity does not match");
    }
    return reservation;
  }

  private ReservationRecord transition(
      ExecutionEvent event, ReservationIdentity identity, AccountReservation reservation) {
    return switch (event.getExecutionType()) {
      case EXECUTION_TYPE_PARTIAL_FILL, EXECUTION_TYPE_FILL ->
          reservationService.applyFill(
              new ApplyFillOperation(
                  identity,
                  new ExecutionFill(
                      new ExecutionFill.ExecutionId(event.getExecId()),
                      ExecutionFill.AggregateSequence.absent(),
                      new ExecutionFill.FillQuantity(
                          parsePositive(event.getFillQty(), "fill_qty")),
                      new ExecutionFill.FillPrice(
                          parsePositive(event.getFillPx(), "fill_px")))));
      case EXECUTION_TYPE_CANCELED, EXECUTION_TYPE_REJECTED ->
          reservationService.release(
              new ReleaseReservationOperation(
                  identity,
                  new ReleaseReservationOperation.ReleaseReason(
                      event.getText().isBlank() ? "MATCHING_TERMINAL" : event.getText()),
                  event.getExecId()));
      default -> ReservationRecord.from(reservation);
    };
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
