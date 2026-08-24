package com.simplematch.accountservice.reservation;

import com.simplematch.accountservice.authority.AccountAuthorityReader;
import com.simplematch.accountservice.authority.AccountId;
import com.simplematch.accountservice.authority.AccountReservation;
import com.simplematch.contracts.common.v1.ReservationStatus;
import java.util.Objects;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Applies typed final Matching Event effects inside the caller-owned Account transaction. */
@Service
@RequiredArgsConstructor
public class FinalMatchingAccountEffectApplicationService
    implements AccountMatchingExecutionHandler {
  @NonNull private final AccountAuthorityReader authorityReader;
  @NonNull private final AccountReservationApplicationService reservationService;

  /** Applies one final-event effect and verifies Matching's resulting-state fact for fills. */
  @Override
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

  private AccountReservation findReservation(
      ReservationIdentity.OrderId orderId,
      AccountId accountId,
      ReservationTerms.InstrumentSymbol symbol) {
    final AccountReservation reservation =
        authorityReader
            .findReservationByOrderId(orderId.value())
            .orElseThrow(() -> new IllegalArgumentException("reservation not found for order"));
    if (!reservation.accountIdentity().equals(accountId)
        || !reservation.terms().symbol().equals(symbol)) {
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

  private ReservationRecord applyFill(
      ReservationIdentity identity, MatchingAccountEffect.Fill fill) {
    final ReservationRecord record =
        reservationService.applyFill(
            new ApplyFillOperation(
                identity,
                new ExecutionFill(
                    fill.executionId(),
                    ExecutionFill.AggregateSequence.absent(),
                    fill.quantity(),
                    fill.price())));
    return requireResultingState(record, fill.resultingState());
  }

  private ReservationRecord applyTerminal(
      ReservationIdentity identity, MatchingAccountEffect.Terminal terminal) {
    return reservationService.release(
        new ReleaseReservationOperation(
            identity, terminal.reason(), terminal.executionId().value()));
  }

  private ReservationRecord requireResultingState(
      ReservationRecord record, MatchingAccountEffect.ResultingState state) {
    final ReservationStatus expected =
        switch (state) {
          case PARTIALLY_FILLED -> ReservationStatus.RESERVATION_STATUS_ACCEPTED;
          case FILLED -> ReservationStatus.RESERVATION_STATUS_APPLIED;
        };
    if (record.status() != expected) {
      throw new IllegalStateException(
          "Matching resulting state does not match Account reservation state");
    }
    return record;
  }
}
