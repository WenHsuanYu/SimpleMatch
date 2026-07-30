package com.simplematch.riskservice.admission;

import com.simplematch.contracts.orders.v2.CancelOrderCommand;
import com.simplematch.contracts.orders.v2.NewOrderCommand;
import com.simplematch.riskservice.outbox.OutboxRecord;
import com.simplematch.riskservice.outbox.OutboxRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** Coordinates the durable admission saga without holding a database transaction across RPC. */
@Service
public class OrderAdmissionApplicationService {
  private static final int TRANSACTION_TIMEOUT_SECONDS = 8;
  private static final Duration RECOVERY_AGE = Duration.ofSeconds(30);
  private final OrderAdmissionValidator validator;
  private final AdmissionJournalRepository journal;
  private final OutboxRepository outbox;
  private final AccountReservationClient account;
  private final AdmissionOutboxFactory events;
  private final AdmissionBackpressurePolicy backpressure;
  private final Clock clock;
  private final TransactionTemplate transactionTemplate;

  /** Creates the durable admission coordinator and its external account adapter. */
  public OrderAdmissionApplicationService(
      OrderAdmissionValidator validator,
      AdmissionJournalRepository journal,
      OutboxRepository outbox,
      AccountReservationClient account,
      AdmissionOutboxFactory events,
      AdmissionBackpressurePolicy backpressure,
      Clock clock,
      TransactionTemplate transactionTemplate) {
    this.validator = Objects.requireNonNull(validator, "validator");
    this.journal = Objects.requireNonNull(journal, "journal");
    this.outbox = Objects.requireNonNull(outbox, "outbox");
    this.account = Objects.requireNonNull(account, "account");
    this.events = Objects.requireNonNull(events, "events");
    this.backpressure = Objects.requireNonNull(backpressure, "backpressure");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.transactionTemplate = Objects.requireNonNull(transactionTemplate, "transactionTemplate");
  }

  /** Validates, journals, calls account outside a transaction, then finalizes admission. */
  public AdmissionResult admit(NewOrderCommand command) {
    final AdmissionCommand validated = validator.validate(command);
    backpressure.check();
    final AdmissionResult begun =
        transactionTemplate.execute(status -> beginAdmissionInTransaction(validated));
    if (begun == null) {
      throw new IllegalStateException("begin admission transaction returned null");
    }
    if (begun.state() != AdmissionState.PENDING) {
      return begun;
    }
    final ReservationOutcome reservation;
    try {
      reservation = account.reserve(validated);
    } catch (RuntimeException failure) {
      throw new AdmissionUnavailableException(failure);
    }
    final AdmissionResult finalized =
        transactionTemplate.execute(
            status ->
                finalizeAdmissionInTransaction(
                    validated.identity().commandId().value(), reservation));
    if (finalized == null) {
      throw new IllegalStateException("finalize admission transaction returned null");
    }
    return finalized;
  }

  /** Durably admits a validated cancellation without a cash reservation RPC. */
  public AdmissionResult admitCancel(CancelOrderCommand command) {
    final AdmissionCommand validated = validator.validateCancel(command);
    backpressure.check();
    final AdmissionResult begun =
        transactionTemplate.execute(status -> beginAdmissionInTransaction(validated));
    if (begun == null) {
      throw new IllegalStateException("begin cancel transaction returned null");
    }
    if (begun.state() != AdmissionState.PENDING) {
      return begun;
    }
    final AdmissionResult finalized =
        transactionTemplate.execute(
            status ->
                finalizeAdmissionInTransaction(
                    validated.identity().commandId().value(), ReservationOutcome.accepted(null)));
    if (finalized == null) {
      throw new IllegalStateException("finalize cancel transaction returned null");
    }
    return finalized;
  }

  /** Validates and starts a pending saga without making a remote call. */
  public AdmissionResult beginAdmission(NewOrderCommand command) {
    final AdmissionCommand validated = validator.validate(command);
    final AdmissionResult result =
        transactionTemplate.execute(status -> beginAdmissionInTransaction(validated));
    if (result == null) {
      throw new IllegalStateException("begin admission transaction returned null");
    }
    return result;
  }

  /** Persists one pending journal row in its own bounded local transaction. */
  @Transactional(timeout = TRANSACTION_TIMEOUT_SECONDS)
  public AdmissionResult beginAdmission(AdmissionCommand command) {
    return beginAdmissionInTransaction(command);
  }

  private AdmissionResult beginAdmissionInTransaction(AdmissionCommand command) {
    final AdmissionJournalEntry existingByCommand =
        journal.findByCommandId(command.identity().commandId().value()).orElse(null);
    if (existingByCommand != null) {
      if (!existingByCommand.matches(command)) {
        throw new AdmissionConflictException();
      }
      return result(existingByCommand);
    }
    final AdmissionJournalEntry existingByBusiness =
        journal.findByBusinessKey(command).orElse(null);
    if (existingByBusiness != null) {
      if (!existingByBusiness.commandId().equals(command.identity().commandId().value())) {
        throw new AdmissionConflictException();
      }
      return result(existingByBusiness);
    }
    final AdmissionJournalEntry pending = AdmissionJournalEntry.pending(command, clock.millis());
    try {
      if (journal.insert(pending)) {
        return result(pending);
      }
      return journal
          .findByCommandId(command.identity().commandId().value())
          .map(
              existing -> {
                if (!existing.matches(command)) {
                  throw new AdmissionConflictException();
                }
                return result(existing);
              })
          .orElseThrow(AdmissionConflictException::new);
    } catch (DuplicateKeyException duplicate) {
      return journal
          .findByCommandId(command.identity().commandId().value())
          .map(
              existing -> {
                if (!existing.matches(command)) {
                  throw new AdmissionConflictException();
                }
                return result(existing);
              })
          .orElseThrow(AdmissionConflictException::new);
    }
  }

  /** Finalizes the journal and terminal event in one local transaction. */
  @Transactional(timeout = TRANSACTION_TIMEOUT_SECONDS)
  public AdmissionResult finalizeAdmission(UUID commandId, ReservationOutcome reservation) {
    return finalizeAdmissionInTransaction(commandId, reservation);
  }

  private AdmissionResult finalizeAdmissionInTransaction(
      UUID commandId, ReservationOutcome reservation) {
    final AdmissionJournalEntry current =
        journal
            .findByCommandId(commandId)
            .orElseThrow(() -> new IllegalArgumentException("pending admission not found"));
    if (current.state() != AdmissionState.PENDING) {
      return result(current);
    }
    final long now = clock.millis();
    final AdmissionState state =
        reservation.accepted() ? AdmissionState.ACCEPTED : AdmissionState.REJECTED;
    final AdmissionJournalEntry terminal =
        new AdmissionJournalEntry(
            current.commandId(),
            current.orderId(),
            current.accountId(),
            current.symbol(),
            current.venueMic(),
            current.side(),
            current.quantity(),
            current.limitPriceUnits(),
            current.orderType(),
            current.tif(),
            current.tradingDay(),
            current.senderCompId(),
            current.targetCompId(),
            current.clOrdId(),
            current.routingSnapshotId(),
            current.routingPartition(),
            state,
            reservation.reservationId(),
            reservation.reasonCode(),
            reservation.reasonDetail(),
            current.version() + 1,
            current.createdAtUnixMs(),
            now);
    journal.update(terminal, current.version());
    final OutboxRecord event = events.create(terminal);
    outbox.insert(event);
    return result(terminal);
  }

  /** Replays pending rows with bounded age and leaves failures retryable. */
  @Scheduled(fixedDelay = 30_000L)
  public int recoverPending() {
    final List<AdmissionJournalEntry> pending =
        journal.findPendingBefore(Instant.now(clock).minus(RECOVERY_AGE), 100);
    int recovered = 0;
    for (AdmissionJournalEntry entry : pending) {
      try {
        final AdmissionCommand command =
            new AdmissionCommand(
                new AdmissionIdentity(
                    new AdmissionIdentity.CommandId(entry.commandId()),
                    new AdmissionIdentity.OrderId(entry.orderId()),
                    new AdmissionIdentity.AccountId(entry.accountId())),
                new AdmissionOrder(
                    new AdmissionOrder.Instrument(
                        new AdmissionOrder.Symbol(entry.symbol()),
                        new AdmissionOrder.VenueMic(entry.venueMic())),
                    new AdmissionOrder.Characteristics(
                        new AdmissionOrder.SideCode(entry.side()),
                        new AdmissionOrder.Quantity(entry.quantity()),
                        new AdmissionOrder.LimitPriceUnits(entry.limitPriceUnits()),
                        new AdmissionOrder.OrderTypeCode(entry.orderType()),
                        new AdmissionOrder.TimeInForceCode(entry.tif())),
                    entry.tradingDay()),
                new AdmissionFixIdentity(
                    new AdmissionFixIdentity.SenderCompId(entry.senderCompId()),
                    new AdmissionFixIdentity.TargetCompId(entry.targetCompId()),
                    new AdmissionFixIdentity.ClOrdId(entry.clOrdId())),
                new AdmissionRoutingReference(
                    new AdmissionRoutingReference.RoutingSnapshotId(entry.routingSnapshotId())));
        final ReservationOutcome outcome =
            "CANCEL".equals(command.order().characteristics().orderType().value())
                ? ReservationOutcome.accepted(null)
                : account.reserve(command);
        transactionTemplate.executeWithoutResult(
            status -> finalizeAdmissionInTransaction(entry.commandId(), outcome));
        recovered++;
      } catch (RuntimeException ignored) {
        // The pending journal remains durable and is eligible for the next bounded recovery pass.
      }
    }
    return recovered;
  }

  private AdmissionResult result(AdmissionJournalEntry entry) {
    return new AdmissionResult(
        entry.commandId(),
        entry.orderId(),
        entry.accountId(),
        entry.state(),
        entry.reservationId(),
        entry.reasonCode(),
        entry.reasonDetail(),
        entry.routingSnapshotId() == null ? "" : entry.routingSnapshotId().toString(),
        entry.routingPartition());
  }
}
