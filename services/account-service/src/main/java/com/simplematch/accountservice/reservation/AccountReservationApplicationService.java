package com.simplematch.accountservice.reservation;

import com.simplematch.accountservice.authority.AccountAuthorityLifecycleWriter;
import com.simplematch.accountservice.authority.AccountAuthorityReader;
import com.simplematch.accountservice.authority.AccountLifecycleOutbox;
import com.simplematch.accountservice.authority.AccountLimit;
import com.simplematch.accountservice.authority.AccountOutboxRepository;
import com.simplematch.accountservice.authority.AccountPosition;
import com.simplematch.accountservice.authority.AccountReservation;
import com.simplematch.config.SimpleMatchUuids;
import com.simplematch.contracts.account.v2.AccountLifecycleEvent;
import com.simplematch.contracts.account.v2.AccountLifecycleState;
import com.simplematch.contracts.common.v1.ReservationStatus;
import com.simplematch.contracts.common.v1.Side;
import com.simplematch.contracts.common.v2.EventMetadata;
import com.simplematch.contracts.common.v2.TwdNotional;
import com.simplematch.contracts.orders.v2.ShareQuantity;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Owns account reservation, release, fill, and authoritative balance transactions. */
@Service
@RequiredArgsConstructor
public class AccountReservationApplicationService implements ReservationService {
  private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
  private static final int TRANSACTION_TIMEOUT_SECONDS = 8;
  private static final String OUTBOX_TOPIC = "account.lifecycle";
  private static final String CONSUMER_NAME = "account-service-execution";

  @NonNull private final AccountAuthorityReader authorityReader;
  @NonNull private final AccountAuthorityLifecycleWriter authorityWriter;
  @NonNull private final AccountOutboxRepository outbox;
  @NonNull private final Clock clock;

  /** Reserves cash or available long position and emits a durable lifecycle event. */
  @Override
  @Transactional(timeout = TRANSACTION_TIMEOUT_SECONDS)
  public ReservationRecord reserve(ReserveOperation operation) {
    Objects.requireNonNull(operation, "operation");
    authorityWriter.claimReservationRequest(operation.requestId(), clock.millis());
    final AccountReservation existing =
        authorityReader.findReservationByRequestId(operation.requestId()).orElse(null);
    if (existing != null) {
      return toLegacy(existing);
    }

    final long now = clock.millis();
    final LocalDate tradingDay = clock.instant().atZone(TAIPEI).toLocalDate();
    final AccountReservation.ReserveOperationSnapshot snapshot =
        new AccountReservation.ReserveOperationSnapshot(
            operation.requestId(),
            operation.orderId(),
            operation.accountId(),
            operation.symbol(),
            operation.side(),
            operation.quantity(),
            operation.limitPrice());
    final BigDecimal notional =
        operation.limitPrice() == null
            ? BigDecimal.ZERO
            : operation.quantity().multiply(operation.limitPrice());
    if (operation.limitPrice() == null) {
      return persistRejected(
          operation,
          snapshot,
          "LIMIT_PRICE_REQUIRED",
          "a limit price is required for reservation",
          now);
    }

    if (operation.side() == Side.SIDE_BUY) {
      final AccountLimit limit =
          authorityReader.findLimitForUpdate(operation.accountId(), tradingDay).orElse(null);
      if (limit == null || limit.availableNotional().compareTo(notional) < 0) {
        return persistRejected(
            operation,
            snapshot,
            "INSUFFICIENT_AVAILABLE_NOTIONAL",
            "available account notional is insufficient",
            now);
      }
      final AccountLimit changed =
          limit.withBalances(
              limit.reservedNotional().add(notional),
              limit.utilizedNotional(),
              limit.availableNotional().subtract(notional),
              limit.version() + 1,
              now);
      authorityWriter.updateLimit(changed, limit.version());
    } else {
      final AccountPosition position =
          authorityReader
              .findPositionForUpdate(operation.accountId(), operation.symbol())
              .orElse(null);
      if (position == null
          || position
                  .longQuantity()
                  .subtract(position.reservedLongQuantity())
                  .compareTo(operation.quantity())
              < 0) {
        return persistRejected(
            operation,
            snapshot,
            "INSUFFICIENT_AVAILABLE_POSITION",
            "available long position is insufficient",
            now);
      }
      final AccountPosition changed =
          new AccountPosition(
              position.accountId(),
              position.symbol(),
              position.longQuantity(),
              position.shortQuantity(),
              position.reservedLongQuantity().add(operation.quantity()),
              position.reservedShortQuantity(),
              position.version() + 1,
              now);
      authorityWriter.updatePosition(changed, position.version());
    }

    final AccountReservation reservation =
        AccountReservation.accepted(operation.orderId(), snapshot, notional, now);
    authorityWriter.insertReservation(reservation);
    emit(reservation, AccountLifecycleState.ACCOUNT_LIFECYCLE_STATE_RESERVED, "", now);
    return toLegacy(reservation);
  }

  /** Returns the current Taiwan-trading-day account limit. */
  @Override
  @Transactional(readOnly = true)
  public AccountLimit getLimits(String accountId) {
    return authorityReader
        .findLimit(accountId, clock.instant().atZone(TAIPEI).toLocalDate())
        .orElseThrow(() -> new IllegalArgumentException("account limit is not provisioned"));
  }

  /** Returns authoritative positions for one account. */
  @Override
  @Transactional(readOnly = true)
  public List<AccountPosition> getPositions(String accountId) {
    return authorityReader.findPositions(accountId);
  }

  /** Releases all remaining cash or position authority for a reservation. */
  @Override
  @Transactional(timeout = TRANSACTION_TIMEOUT_SECONDS)
  public ReservationRecord release(ReleaseReservationOperation operation) {
    Objects.requireNonNull(operation, "operation");
    final ReservationIdentity identity = operation.reservation();
    final AccountReservation reservation =
        authorityReader
            .findReservationForUpdate(identity.reservationId().value())
            .orElseThrow(() -> new IllegalArgumentException("reservation not found"));
    if (!reservation.requestId().equals(identity.requestId().value())
        || !reservation.orderId().equals(identity.orderId().value())) {
      throw new IllegalArgumentException("reservation identity does not match request");
    }
    if (reservation.status() == ReservationStatus.RESERVATION_STATUS_RELEASED
        || reservation.status() == ReservationStatus.RESERVATION_STATUS_REJECTED
        || reservation.remainingQuantity().signum() == 0) {
      return toLegacy(reservation);
    }
    final long now = clock.millis();
    AccountAuthorityTransitions.releaseCancelledAuthority(
        authorityReader, authorityWriter, clock, reservation, now);
    final AccountReservation changed =
        new AccountReservation(
            reservation.reservationId(),
            reservation.requestId(),
            reservation.orderId(),
            reservation.accountId(),
            reservation.symbol(),
            reservation.side(),
            reservation.quantity(),
            BigDecimal.ZERO,
            reservation.filledQuantity(),
            reservation.limitPrice(),
            BigDecimal.ZERO,
            ReservationStatus.RESERVATION_STATUS_RELEASED,
            operation.reason().value(),
            "",
            reservation.version() + 1,
            reservation.createdAtUnixMs(),
            now);
    authorityWriter.updateReservation(changed, reservation.version());
    emit(
        changed, AccountLifecycleState.ACCOUNT_LIFECYCLE_STATE_RELEASED, changed.reasonCode(), now);
    return toLegacy(changed);
  }

  /** Applies one execution fill once, using the account inbox as the deduplication boundary. */
  @Override
  @Transactional(timeout = TRANSACTION_TIMEOUT_SECONDS)
  public ReservationRecord applyFill(ApplyFillOperation operation) {
    Objects.requireNonNull(operation, "operation");
    final ReservationIdentity identity = operation.reservation();
    final ExecutionFill fill = operation.fill();
    if (!authorityWriter.claimInbox(
        CONSUMER_NAME,
        fill.executionId().value(),
        identity.orderId().value(),
        fill.aggregateSequence().value(),
        clock.millis())) {
      return authorityReader
          .findReservationForUpdate(identity.reservationId().value())
          .map(this::toLegacy)
          .orElseThrow(() -> new IllegalArgumentException("reservation not found"));
    }
    final AccountReservation reservation =
        authorityReader
            .findReservationForUpdate(identity.reservationId().value())
            .orElseThrow(() -> new IllegalArgumentException("reservation not found"));
    AccountAuthorityTransitions.validateFill(reservation, identity, fill);
    final long now = clock.millis();
    final BigDecimal fillNotional = fill.notional();
    final BigDecimal remaining = reservation.remainingQuantity().subtract(fill.quantity().value());
    final BigDecimal releasedNotional =
        remaining.signum() == 0
            ? reservation.reservedNotional()
            : reservation.reservedNotional().min(fillNotional);
    AccountAuthorityTransitions.applyFilledAuthority(
        authorityReader, authorityWriter, clock, reservation, fill, releasedNotional, now);
    final ReservationStatus status =
        remaining.signum() == 0
            ? ReservationStatus.RESERVATION_STATUS_APPLIED
            : ReservationStatus.RESERVATION_STATUS_ACCEPTED;
    final AccountReservation changed =
        new AccountReservation(
            reservation.reservationId(),
            reservation.requestId(),
            reservation.orderId(),
            reservation.accountId(),
            reservation.symbol(),
            reservation.side(),
            reservation.quantity(),
            remaining,
            reservation.filledQuantity().add(fill.quantity().value()),
            reservation.limitPrice(),
            reservation.reservedNotional().subtract(releasedNotional),
            status,
            "",
            "",
            reservation.version() + 1,
            reservation.createdAtUnixMs(),
            now);
    authorityWriter.updateReservation(changed, reservation.version());
    emit(
        changed,
        status == ReservationStatus.RESERVATION_STATUS_APPLIED
            ? AccountLifecycleState.ACCOUNT_LIFECYCLE_STATE_FILLED
            : AccountLifecycleState.ACCOUNT_LIFECYCLE_STATE_RESERVED,
        "",
        now);
    return toLegacy(changed);
  }

  /** Provisions an account-wide daily cash limit for controlled administration. */
  @Transactional
  public void provisionLimit(String accountId, LocalDate tradingDay, BigDecimal totalNotional) {
    authorityWriter.insertLimit(
        AccountLimit.provisioned(accountId, tradingDay, totalNotional, clock.millis()));
  }

  /** Provisions an empty position row for controlled administration. */
  @Transactional
  public void provisionPosition(String accountId, String symbol) {
    authorityWriter.insertPosition(AccountPosition.provisioned(accountId, symbol, clock.millis()));
  }

  private ReservationRecord persistRejected(
      ReserveOperation operation,
      AccountReservation.ReserveOperationSnapshot snapshot,
      String reasonCode,
      String reasonText,
      long now) {
    final AccountReservation rejected =
        AccountReservation.rejected(operation.orderId(), snapshot, reasonCode, reasonText, now);
    authorityWriter.insertReservation(rejected);
    emit(rejected, AccountLifecycleState.ACCOUNT_LIFECYCLE_STATE_REJECTED, reasonCode, now);
    return toLegacy(rejected);
  }

  private void emit(
      AccountReservation reservation, AccountLifecycleState state, String reasonCode, long now) {
    final String eventId = SimpleMatchUuids.uuidV7().toString();
    final AccountLifecycleEvent event =
        AccountLifecycleEvent.newBuilder()
            .setMetadata(
                EventMetadata.newBuilder()
                    .setSchemaVersion("v2")
                    .setEventId(eventId)
                    .setCreatedAtUnixMs(now)
                    .setSourceService("account-service")
                    .build())
            .setReservationId(reservation.reservationId())
            .setOrderId(reservation.orderId())
            .setAccountId(reservation.accountId())
            .setState(state)
            .setReservedNotional(
                TwdNotional.newBuilder()
                    .setUnits(
                        reservation
                            .reservedNotional()
                            .movePointRight(4)
                            .setScale(0, RoundingMode.UNNECESSARY)
                            .longValueExact())
                    .build())
            .setReservedQuantity(
                ShareQuantity.newBuilder()
                    .setShares(reservation.remainingQuantity().longValueExact())
                    .build())
            .setReasonCode(reasonCode == null ? "" : reasonCode)
            .setReasonDetail(reservation.reasonText())
            .build();
    outbox.insert(
        new AccountLifecycleOutbox(
            java.util.UUID.fromString(eventId),
            OUTBOX_TOPIC,
            reservation.orderId(),
            event.toByteArray(),
            AccountLifecycleEvent.getDescriptor().getFullName(),
            "{\"schema_version\":\"v2\"}",
            "account_reservation",
            reservation.reservationId(),
            now));
  }

  private ReservationRecord toLegacy(AccountReservation reservation) {
    return new ReservationRecord(
        reservation.reservationId(),
        reservation.requestId(),
        reservation.orderId(),
        reservation.accountId(),
        reservation.symbol(),
        reservation.side(),
        reservation.quantity(),
        reservation.limitPrice(),
        reservation.reservedNotional(),
        reservation.status(),
        reservation.reasonCode(),
        reservation.reasonText(),
        reservation.createdAtUnixMs(),
        reservation.updatedAtUnixMs());
  }
}
