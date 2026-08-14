package com.simplematch.riskservice.admission;

import com.simplematch.contracts.orders.v2.CancelOrderCommand;
import com.simplematch.contracts.orders.v2.NewOrderCommand;
import java.util.Optional;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Coordinates the durable admission saga without holding a database transaction across RPC. */
@Service
@RequiredArgsConstructor
public class OrderAdmissionApplicationService {
  private final @NonNull OrderAdmissionValidator validator;
  private final @NonNull AdmissionLifecycleTransactions lifecycleTransactions;
  private final @NonNull AccountReservationClient account;
  private final @NonNull AdmissionBackpressurePolicy backpressure;

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
    } catch (AdmissionValidationException
        | AdmissionConflictException
        | AdmissionUnavailableException
        | AdmissionInvariantException
        | AdmissionAccountFailureException knownFailure) {
      throw knownFailure;
    } catch (RuntimeException failure) {
      throw new AdmissionUnavailableException(failure);
    }
    return lifecycleTransactions.finalizeAdmission(
        validated.identity().commandId().value(), reservation);
  }

  /** Durably admits cancellation through the same durable journal and terminal outbox. */
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

  /** Returns the durable outcome currently visible for one admission command. */
  public Optional<AdmissionResult> findOutcome(UUID commandId) {
    return lifecycleTransactions.findAdmission(commandId);
  }
}
