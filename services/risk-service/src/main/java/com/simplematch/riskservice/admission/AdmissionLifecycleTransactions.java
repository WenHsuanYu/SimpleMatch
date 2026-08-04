package com.simplematch.riskservice.admission;

import com.simplematch.riskservice.outbox.OutboxRecord;
import com.simplematch.riskservice.outbox.OutboxRepository;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.support.TransactionTemplate;

/** Owns the bounded local transactions for pending and terminal Admission state. */
public final class AdmissionLifecycleTransactions {
  private final AdmissionJournalRepository journal;
  private final OutboxRepository outbox;
  private final AdmissionOutboxFactory events;
  private final AdmissionRoutingPolicyResolver routingPolicyResolver;
  private final Clock clock;
  private final TransactionTemplate transactionTemplate;

  /**
   * Creates the application module over the journal, outbox, event, clock, and transaction seams.
   *
   * @param journal durable Admission journal adapter
   * @param outbox durable Admission outbox adapter
   * @param events terminal Admission event factory
   * @param routingPolicyResolver local authoritative Routing Policy selector
   * @param clock source of journal and event timestamps
   * @param transactionTemplate bounded local transaction template
   */
  public AdmissionLifecycleTransactions(
      AdmissionJournalRepository journal,
      OutboxRepository outbox,
      AdmissionOutboxFactory events,
      AdmissionRoutingPolicyResolver routingPolicyResolver,
      Clock clock,
      TransactionTemplate transactionTemplate) {
    this.journal = Objects.requireNonNull(journal, "journal");
    this.outbox = Objects.requireNonNull(outbox, "outbox");
    this.events = Objects.requireNonNull(events, "events");
    this.routingPolicyResolver =
        Objects.requireNonNull(routingPolicyResolver, "routingPolicyResolver");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.transactionTemplate = Objects.requireNonNull(transactionTemplate, "transactionTemplate");
  }

  /** Persists one pending Admission in one bounded local transaction. */
  public AdmissionResult beginAdmission(AdmissionCommand command) {
    Objects.requireNonNull(command, "command");
    return requireResult(
        transactionTemplate.execute(status -> beginAdmissionInTransaction(command)),
        "begin admission");
  }

  /** Finalizes the journal and terminal event in one bounded local transaction. */
  public AdmissionResult finalizeAdmission(UUID commandId, ReservationOutcome reservation) {
    Objects.requireNonNull(commandId, "commandId");
    return requireResult(
        transactionTemplate.execute(
            status -> finalizeAdmissionInTransaction(commandId, reservation)),
        "finalize admission");
  }

  /** Applies pending idempotency and conflict rules inside the local transaction. */
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
      if (!existingByBusiness
          .command()
          .identity()
          .commandId()
          .value()
          .equals(command.identity().commandId().value())) {
        throw new AdmissionConflictException();
      }
      return result(existingByBusiness);
    }
    final AdmissionDeliveryRoute route =
        routingPolicyResolver.resolve(command, clock.instant());
    final AdmissionJournalEntry pending =
        AdmissionJournalEntry.pending(command, route, clock.millis());
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

  /** Applies the terminal lifecycle and journal/outbox atomicity inside the local transaction. */
  private AdmissionResult finalizeAdmissionInTransaction(
      UUID commandId, ReservationOutcome reservation) {
    final AdmissionJournalEntry current =
        journal
            .findByCommandId(commandId)
            .orElseThrow(() -> new IllegalArgumentException("pending admission not found"));
    if (current.lifecycle().state() != AdmissionState.PENDING) {
      return result(current);
    }
    final long now = clock.millis();
    final AdmissionJournalEntry terminal = current.finalizeWith(reservation, now);
    journal.update(terminal, current.lifecycle().version());
    final OutboxRecord event = events.create(terminal);
    outbox.insert(event);
    return result(terminal);
  }

  private static AdmissionResult requireResult(AdmissionResult result, String operation) {
    if (result == null) {
      throw new IllegalStateException(operation + " transaction returned null");
    }
    return result;
  }

  private AdmissionResult result(AdmissionJournalEntry entry) {
    return AdmissionResult.from(entry);
  }
}
