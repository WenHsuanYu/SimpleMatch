package com.simplematch.accountservice.reservation;

import com.simplematch.accountservice.authority.AccountAuthorityLifecycleWriter;
import com.simplematch.accountservice.authority.AccountAuthorityReader;
import com.simplematch.accountservice.authority.AccountId;
import com.simplematch.accountservice.authority.AccountLifecycleOutbox;
import com.simplematch.accountservice.authority.AccountLimit;
import com.simplematch.accountservice.authority.AccountLimitLedger;
import com.simplematch.accountservice.authority.AccountOutboxRepository;
import com.simplematch.accountservice.authority.AccountPosition;
import com.simplematch.accountservice.authority.AccountPositionInventory;
import com.simplematch.accountservice.authority.AccountReservation;
import com.simplematch.accountservice.authority.ReservationOwnership;
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
public class AccountReservationApplicationService {
  private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
  private static final int TRANSACTION_TIMEOUT_SECONDS = 8;
  private static final String OUTBOX_TOPIC = "account.lifecycle";
  private static final String CONSUMER_NAME = "account-service-execution";

  @NonNull private final AccountAuthorityReader authorityReader;
  @NonNull private final AccountAuthorityLifecycleWriter authorityWriter;
  @NonNull private final AccountOutboxRepository outbox;
  @NonNull private final Clock clock;

  /** Reserves cash or available long position and emits a durable lifecycle event. */
  @Transactional(timeout = TRANSACTION_TIMEOUT_SECONDS)
  public ReservationRecord reserve(ReserveOperation operation) {
    Objects.requireNonNull(operation, "operation");
    authorityWriter.claimReservationRequest(operation.requestId(), clock.millis());
    final AccountReservation existing =
        authorityReader.findReservationByRequestId(operation.requestId()).orElse(null);
    if (existing != null) {
      return replay(existing, operation);
    }

    final long now = clock.millis();
    final LocalDate tradingDay = clock.instant().atZone(TAIPEI).toLocalDate();
    final BigDecimal notional =
        operation.limitPrice() == null
            ? BigDecimal.ZERO
            : operation.quantity().multiply(operation.limitPrice());
    if (operation.limitPrice() == null) {
      return persistRejected(
          operation,
          "LIMIT_PRICE_REQUIRED",
          "a limit price is required for reservation",
          now);
    }

    if (operation.side() == Side.SIDE_BUY) {
      final AccountLimit limit =
          authorityReader.findLimitForUpdate(operation.accountIdentity(), tradingDay).orElse(null);
      if (limit == null || limit.availableNotional().compareTo(notional) < 0) {
        return persistRejected(
            operation,
            "INSUFFICIENT_AVAILABLE_NOTIONAL",
            "available account notional is insufficient",
            now);
      }
      final AccountLimit changed =
          limit.withLedger(
              new AccountLimitLedger(
                  limit.limitTotalNotional(),
                  limit.reservedNotional().add(notional),
                  limit.utilizedNotional(),
                  limit.availableNotional().subtract(notional)),
              limit.revision().next(now));
      authorityWriter.updateLimit(changed, limit.version());
    } else {
      final AccountPosition position =
          authorityReader
              .findPositionForUpdate(operation.accountIdentity(), operation.symbol())
              .orElse(null);
      if (position == null
          || position
                  .longQuantity()
                  .subtract(position.reservedLongQuantity())
                  .compareTo(operation.quantity())
              < 0) {
        return persistRejected(
            operation,
            "INSUFFICIENT_AVAILABLE_POSITION",
            "available long position is insufficient",
            now);
      }
      final AccountPosition changed =
          position.withInventory(
              new AccountPositionInventory(
                  position.longQuantity(),
                  position.shortQuantity(),
                  position.reservedLongQuantity().add(operation.quantity()),
                  position.reservedShortQuantity()),
              position.revision().next(now));
      authorityWriter.updatePosition(changed, position.version());
    }

    final AccountReservation reservation =
        AccountReservation.accepted(
            operation.reservationIdentity(),
            new ReservationOwnership(operation.accountIdentity()),
            operation.terms(),
            notional,
            now);
    authorityWriter.insertReservation(reservation);
    emit(reservation, AccountLifecycleState.ACCOUNT_LIFECYCLE_STATE_RESERVED, "", now);
    return ReservationRecord.from(reservation);
  }

  /** Returns the current Taiwan-trading-day account limit. */
  @Transactional(readOnly = true)
  public AccountLimit getLimits(String accountId) {
    return authorityReader
        .findLimit(AccountId.parse(accountId), clock.instant().atZone(TAIPEI).toLocalDate())
        .orElseThrow(() -> new IllegalArgumentException("account limit is not provisioned"));
  }

  /** Returns authoritative positions for one account. */
  @Transactional(readOnly = true)
  public List<AccountPosition> getPositions(String accountId) {
    return authorityReader.findPositions(AccountId.parse(accountId));
  }

  /** Releases all remaining cash or position authority for a reservation. */
  @Transactional(timeout = TRANSACTION_TIMEOUT_SECONDS)
  public ReservationRecord release(ReleaseReservationOperation operation) {
    Objects.requireNonNull(operation, "operation");
    final ReservationIdentity identity = operation.reservation();
    if (operation.sourceEventId() != null
        && !authorityWriter.claimInbox(
            CONSUMER_NAME,
            operation.sourceEventId(),
            identity.orderId().value(),
            null,
            clock.millis())) {
      return authorityReader
          .findReservationForUpdate(identity.reservationId().value())
          .map(ReservationRecord::from)
          .orElseThrow(() -> new IllegalArgumentException("reservation not found"));
    }
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
      return ReservationRecord.from(reservation);
    }
    final long now = clock.millis();
    AccountAuthorityTransitions.releaseCancelledAuthority(
        authorityReader, authorityWriter, clock, reservation, now);
    final AccountReservation changed = reservation.release(operation.reason(), now);
    authorityWriter.updateReservation(changed, reservation.version());
    emit(
        changed, AccountLifecycleState.ACCOUNT_LIFECYCLE_STATE_RELEASED, changed.reasonCode(), now);
    return ReservationRecord.from(changed);
  }

  /** Applies one execution fill once, using the account inbox as the deduplication boundary. */
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
          .map(ReservationRecord::from)
          .orElseThrow(() -> new IllegalArgumentException("reservation not found"));
    }
    final AccountReservation reservation =
        authorityReader
            .findReservationForUpdate(identity.reservationId().value())
            .orElseThrow(() -> new IllegalArgumentException("reservation not found"));
    AccountAuthorityTransitions.validateFill(reservation, identity, fill);
    final long now = clock.millis();
    final BigDecimal releasedNotional =
        fill.quantity().value().compareTo(reservation.remainingQuantity()) == 0
            ? reservation.reservedNotional()
            : reservation.reservedNotionalReleasedBy(fill);
    AccountAuthorityTransitions.applyFilledAuthority(
        authorityReader, authorityWriter, clock, reservation, fill, releasedNotional, now);
    final AccountReservation changed = reservation.applyFill(fill, now);
    final ReservationStatus status = changed.status();
    authorityWriter.updateReservation(changed, reservation.version());
    emit(
        changed,
        status == ReservationStatus.RESERVATION_STATUS_APPLIED
            ? AccountLifecycleState.ACCOUNT_LIFECYCLE_STATE_FILLED
            : AccountLifecycleState.ACCOUNT_LIFECYCLE_STATE_RESERVED,
        "",
        now);
    return ReservationRecord.from(changed);
  }

  /** Provisions an account-wide daily cash limit for controlled administration. */
  @Transactional
  public void provisionLimit(String accountId, LocalDate tradingDay, BigDecimal totalNotional) {
    authorityWriter.insertLimit(
        AccountLimit.provisioned(
            AccountId.parse(accountId), tradingDay, totalNotional, clock.millis()));
  }

  /** Provisions an empty position row for controlled administration. */
  @Transactional
  public void provisionPosition(String accountId, String symbol) {
    authorityWriter.insertPosition(
        AccountPosition.provisioned(AccountId.parse(accountId), symbol, clock.millis()));
  }

  private ReservationRecord persistRejected(
      ReserveOperation operation,
      String reasonCode,
      String reasonText,
      long now) {
    final AccountReservation rejected =
        AccountReservation.rejected(
        operation.reservationIdentity(),
            new ReservationOwnership(operation.accountIdentity()),
            operation.terms(),
            reasonCode,
            reasonText,
            now);
    authorityWriter.insertReservation(rejected);
    emit(rejected, AccountLifecycleState.ACCOUNT_LIFECYCLE_STATE_REJECTED, reasonCode, now);
    return ReservationRecord.from(rejected);
  }

  private ReservationRecord replay(AccountReservation existing, ReserveOperation operation) {
    if (!existing.hasEquivalentRequestFacts(operation)) {
      throw new ReservationRequestConflictException(operation.requestId());
    }
    return ReservationRecord.from(existing);
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
            new AccountLifecycleOutbox.EventIdentity(java.util.UUID.fromString(eventId)),
            new AccountLifecycleOutbox.Destination(OUTBOX_TOPIC, reservation.accountId()),
            new AccountLifecycleOutbox.Payload(
                event.toByteArray(),
                AccountLifecycleEvent.getDescriptor().getFullName(),
                "{\"schema_version\":\"v2\"}"),
            new AccountLifecycleOutbox.AggregateReference(
                "account_reservation", reservation.reservationId()),
            now));
  }

}
