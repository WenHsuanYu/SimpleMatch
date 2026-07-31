package com.simplematch.accountservice.reservation;

import com.simplematch.accountservice.authority.AccountAuthorityLifecycleWriter;
import com.simplematch.accountservice.authority.AccountAuthorityReader;
import com.simplematch.accountservice.authority.AccountLimit;
import com.simplematch.accountservice.authority.AccountLimitLedger;
import com.simplematch.accountservice.authority.AccountPosition;
import com.simplematch.accountservice.authority.AccountPositionInventory;
import com.simplematch.accountservice.authority.AccountReservation;
import com.simplematch.contracts.common.v1.ReservationStatus;
import com.simplematch.contracts.common.v1.Side;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

/** Applies authoritative balance changes within the reservation transaction. */
final class AccountAuthorityTransitions {
  private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");

  private AccountAuthorityTransitions() {}

  static void releaseCancelledAuthority(
      AccountAuthorityReader authorityReader,
      AccountAuthorityLifecycleWriter authorityWriter,
      Clock clock,
      AccountReservation reservation,
      long now) {
    final LocalDate tradingDay = clock.instant().atZone(TAIPEI).toLocalDate();
    if (reservation.side() == Side.SIDE_BUY) {
      final AccountLimit limit =
          authorityReader
              .findLimitForUpdate(reservation.accountId(), tradingDay)
              .orElseThrow(() -> new IllegalStateException("account limit is not provisioned"));
      final AccountLimit changed =
          limit.withLedger(
              new AccountLimitLedger(
                  limit.limitTotalNotional(),
                  limit
                      .reservedNotional()
                      .subtract(reservation.reservedNotional())
                      .max(BigDecimal.ZERO),
                  limit.utilizedNotional(),
                  limit.availableNotional().add(reservation.reservedNotional())),
              limit.revision().next(now));
      authorityWriter.updateLimit(changed, limit.version());
      return;
    }
    final AccountPosition position =
        authorityReader
            .findPositionForUpdate(reservation.accountId(), reservation.symbol())
            .orElseThrow(() -> new IllegalStateException("position is not provisioned"));
    final BigDecimal released =
        position.reservedLongQuantity().min(reservation.remainingQuantity());
    final AccountPosition changed =
        position.withInventory(
            new AccountPositionInventory(
                position.longQuantity(),
                position.shortQuantity(),
                position.reservedLongQuantity().subtract(released),
                position.reservedShortQuantity()),
            position.revision().next(now));
    authorityWriter.updatePosition(changed, position.version());
  }

  static void applyFilledAuthority(
      AccountAuthorityReader authorityReader,
      AccountAuthorityLifecycleWriter authorityWriter,
      Clock clock,
      AccountReservation reservation,
      ExecutionFill fill,
      BigDecimal releasedNotional,
      long now) {
    final LocalDate tradingDay = clock.instant().atZone(TAIPEI).toLocalDate();
    if (reservation.side() == Side.SIDE_BUY) {
      final AccountLimit limit =
          authorityReader
              .findLimitForUpdate(reservation.accountId(), tradingDay)
              .orElseThrow(() -> new IllegalStateException("account limit is not provisioned"));
      final AccountLimit changed =
          limit.withLedger(
              new AccountLimitLedger(
                  limit.limitTotalNotional(),
                  limit.reservedNotional().subtract(releasedNotional).max(BigDecimal.ZERO),
                  limit.utilizedNotional().add(fill.notional()),
                  limit.availableNotional().add(releasedNotional).subtract(fill.notional())),
              limit.revision().next(now));
      authorityWriter.updateLimit(changed, limit.version());
      final AccountPosition position =
          authorityReader
              .findPositionForUpdate(reservation.accountId(), reservation.symbol())
              .orElseThrow(() -> new IllegalStateException("position is not provisioned"));
      final AccountPosition changedPosition =
          position.withInventory(
              new AccountPositionInventory(
                  position.longQuantity().add(fill.quantity().value()),
                  position.shortQuantity(),
                  position.reservedLongQuantity(),
                  position.reservedShortQuantity()),
              position.revision().next(now));
      authorityWriter.updatePosition(changedPosition, position.version());
      return;
    }
    final AccountPosition position =
        authorityReader
            .findPositionForUpdate(reservation.accountId(), reservation.symbol())
            .orElseThrow(() -> new IllegalStateException("position is not provisioned"));
    final BigDecimal released = position.reservedLongQuantity().min(fill.quantity().value());
    final AccountPosition changed =
        position.withInventory(
            new AccountPositionInventory(
                position.longQuantity().subtract(released),
                position.shortQuantity(),
                position.reservedLongQuantity().subtract(released),
                position.reservedShortQuantity()),
            position.revision().next(now));
    authorityWriter.updatePosition(changed, position.version());
  }

  static void validateFill(
      AccountReservation reservation, ReservationIdentity identity, ExecutionFill fill) {
    if (!reservation.requestId().equals(identity.requestId().value())
        || !reservation.orderId().equals(identity.orderId().value())) {
      throw new IllegalArgumentException("reservation identity does not match fill");
    }
    if (reservation.status() != ReservationStatus.RESERVATION_STATUS_ACCEPTED) {
      throw new IllegalArgumentException("reservation is not active");
    }
    if (fill.quantity().value().compareTo(reservation.remainingQuantity()) > 0) {
      throw new IllegalArgumentException("fill quantity exceeds remaining reservation quantity");
    }
  }
}
