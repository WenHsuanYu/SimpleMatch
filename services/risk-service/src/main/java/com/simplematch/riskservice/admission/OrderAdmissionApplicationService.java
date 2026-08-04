package com.simplematch.riskservice.admission;

import com.simplematch.contracts.orders.v2.CancelOrderCommand;
import com.simplematch.contracts.orders.v2.NewOrderCommand;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Coordinates the durable admission saga without holding a database transaction across RPC. */
@Service
@RequiredArgsConstructor
public class OrderAdmissionApplicationService {
  private static final Duration RECOVERY_AGE = Duration.ofSeconds(30);
  private final @NonNull OrderAdmissionValidator validator;
  private final @NonNull AdmissionLifecycleTransactions lifecycleTransactions;
  private final @NonNull AdmissionJournalRepository journal;
  private final @NonNull AccountReservationClient account;
  private final @NonNull AdmissionBackpressurePolicy backpressure;
  private final @NonNull Clock clock;

  /** Validates, journals, calls account outside a transaction, then finalizes admission. */
  public AdmissionResult admit(NewOrderCommand command) {
    final AdmissionCommand validated = validator.validate(command);
    backpressure.check();
    final AdmissionResult begun = lifecycleTransactions.beginAdmission(validated);
    if (begun.state() != AdmissionState.PENDING) {
      return begun;
    }
    final ReservationOutcome reservation;
    try {
      reservation = account.reserve(validated);
    } catch (RuntimeException failure) {
      throw new AdmissionUnavailableException(failure);
    }
    return lifecycleTransactions.finalizeAdmission(
        validated.identity().commandId().value(), reservation);
  }

  /** Durably admits a validated cancellation without a cash reservation RPC. */
  public AdmissionResult admitCancel(CancelOrderCommand command) {
    final AdmissionCommand validated = validator.validateCancel(command);
    backpressure.check();
    final AdmissionResult begun = lifecycleTransactions.beginAdmission(validated);
    if (begun.state() != AdmissionState.PENDING) {
      return begun;
    }
    return lifecycleTransactions.finalizeAdmission(
        validated.identity().commandId().value(), ReservationOutcome.accepted(null));
  }

  /** Validates and starts a pending saga without making a remote call. */
  public AdmissionResult beginAdmission(NewOrderCommand command) {
    final AdmissionCommand validated = validator.validate(command);
    return lifecycleTransactions.beginAdmission(validated);
  }

  /** Persists one pending journal row in its own bounded local transaction. */
  public AdmissionResult beginAdmission(AdmissionCommand command) {
    return lifecycleTransactions.beginAdmission(command);
  }

  /** Finalizes the journal and terminal event in one local transaction. */
  public AdmissionResult finalizeAdmission(UUID commandId, ReservationOutcome reservation) {
    return lifecycleTransactions.finalizeAdmission(commandId, reservation);
  }

  /** Replays pending rows with bounded age and leaves failures retryable. */
  @Scheduled(fixedDelay = 30_000L)
  public int recoverPending() {
    final List<AdmissionJournalEntry> pending =
        journal.findPendingBefore(Instant.now(clock).minus(RECOVERY_AGE), 100);
    int recovered = 0;
    for (AdmissionJournalEntry entry : pending) {
      try {
        final AdmissionCommand command = entry.command();
        final ReservationOutcome outcome =
            command.order().isCancellation()
                ? ReservationOutcome.accepted(null)
                : account.reserve(command);
        lifecycleTransactions.finalizeAdmission(
            entry.command().identity().commandId().value(), outcome);
        recovered++;
      } catch (RuntimeException ignored) {
        // The pending journal remains durable and is eligible for the next bounded recovery pass.
      }
    }
    return recovered;
  }
}
