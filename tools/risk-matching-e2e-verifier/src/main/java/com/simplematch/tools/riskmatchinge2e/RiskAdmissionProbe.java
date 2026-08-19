package com.simplematch.tools.riskmatchinge2e;

import com.simplematch.contracts.orders.v2.NewOrderCommand;
import com.simplematch.contracts.risk.v2.GetAdmissionOutcomeRequest;
import com.simplematch.contracts.risk.v2.GetAdmissionOutcomeResponse;
import com.simplematch.contracts.risk.v2.OrderAdmissionResponse;
import com.simplematch.contracts.risk.v2.OrderAdmissionServiceGrpc;
import com.simplematch.tools.riskmatchinge2e.RiskMatchingScenario.Scenario;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** Observes Risk submission and durable reconciliation without owning saga recovery. */
final class RiskAdmissionProbe {
  private static final Duration LOOKUP_ATTEMPT_TIMEOUT = Duration.ofSeconds(2);
  private static final Duration POLL_INTERVAL = Duration.ofMillis(500);

  private final RiskGateway gateway;
  private final Sleeper sleeper;
  private final LongSupplier nanoTime;

  RiskAdmissionProbe(ManagedChannel channel) {
    this(new GrpcRiskGateway(channel), RiskAdmissionProbe::sleepThread, System::nanoTime);
  }

  RiskAdmissionProbe(RiskGateway gateway, Sleeper sleeper, LongSupplier nanoTime) {
    this.gateway = Objects.requireNonNull(gateway, "risk gateway is required");
    this.sleeper = Objects.requireNonNull(sleeper, "sleeper is required");
    this.nanoTime = Objects.requireNonNull(nanoTime, "nano time source is required");
  }

  SubmissionObservation submit(Scenario scenario, VerificationDeadline deadline) {
    Objects.requireNonNull(scenario, "scenario is required");
    Objects.requireNonNull(deadline, "deadline is required");
    final long startedAtNanos = nanoTime.getAsLong();
    final Duration remaining =
        deadline.requireRemaining(
            VerificationFailure.Stage.ADMISSION_SUBMISSION,
            VerificationFailure.Code.ADMISSION_SUBMISSION_FAILED,
            "verifier deadline expired before Risk submission");
    try {
      final OrderAdmissionResponse response = gateway.submit(scenario.request(), remaining);
      return new SubmissionObservation(
          Status.Code.OK, Optional.of(response), startedAtNanos, nanoTime.getAsLong());
    } catch (StatusRuntimeException failure) {
      if (Status.fromThrowable(failure).getCode() == Status.Code.UNAVAILABLE) {
        return new SubmissionObservation(
            Status.Code.UNAVAILABLE, Optional.empty(), startedAtNanos, nanoTime.getAsLong());
      }
      throw new VerificationFailure(
          VerificationFailure.Stage.ADMISSION_SUBMISSION,
          VerificationFailure.Code.ADMISSION_SUBMISSION_FAILED,
          "Risk submission failed with gRPC status " + Status.fromThrowable(failure).getCode(),
          failure);
    }
  }

  AdmissionObservation awaitAccepted(
      Scenario scenario,
      SubmissionObservation submission,
      VerificationDeadline deadline) {
    Objects.requireNonNull(scenario, "scenario is required");
    Objects.requireNonNull(submission, "submission observation is required");
    Objects.requireNonNull(deadline, "deadline is required");

    if (submission.grpcCode() == Status.Code.OK) {
      return synchronousOutcome(scenario, submission);
    }
    if (submission.grpcCode() != Status.Code.UNAVAILABLE) {
      throw new VerificationFailure(
          VerificationFailure.Stage.ADMISSION_SUBMISSION,
          VerificationFailure.Code.ADMISSION_SUBMISSION_FAILED,
          "unsupported Risk submission status " + submission.grpcCode());
    }
    return reconcile(scenario, submission, deadline);
  }

  private AdmissionObservation synchronousOutcome(
      Scenario scenario, SubmissionObservation submission) {
    final OrderAdmissionResponse response =
        submission.response().orElseThrow(
            () ->
                new VerificationFailure(
                    VerificationFailure.Stage.ADMISSION_SUBMISSION,
                    VerificationFailure.Code.ADMISSION_SUBMISSION_FAILED,
                    "Risk returned OK without an admission response"));
    if (response.hasRejected()) {
      throw RiskAdmissionSemantics.rejected(
          VerificationFailure.Stage.ADMISSION_SUBMISSION,
          response.getRejected().getReason().name(),
          response.getRejected().getReasonDetail());
    }
    RiskAdmissionSemantics.validateSynchronousAccepted(scenario, response);
    return acceptedObservation(
        scenario,
        AdmissionPath.SYNCHRONOUS_ACCEPTED,
        submission,
        false,
        0,
        "ACCEPTED");
  }

  private AdmissionObservation reconcile(
      Scenario scenario,
      SubmissionObservation submission,
      VerificationDeadline deadline) {
    boolean pendingObserved = false;
    int attempts = 0;
    while (true) {
      final Duration remaining =
          deadline.requireRemaining(
              VerificationFailure.Stage.ADMISSION_RECONCILIATION,
              VerificationFailure.Code.ADMISSION_REMAINED_PENDING,
              "Risk admission remained pending until the verifier deadline");
      attempts++;
      final GetAdmissionOutcomeResponse response;
      try {
        response =
            gateway.lookup(
                scenario.command().commandId().toString(),
                min(remaining, LOOKUP_ATTEMPT_TIMEOUT));
      } catch (StatusRuntimeException failure) {
        if (Status.fromThrowable(failure).getCode() == Status.Code.UNAVAILABLE) {
          waitForNextLookup(deadline);
          continue;
        }
        throw new VerificationFailure(
            VerificationFailure.Stage.ADMISSION_RECONCILIATION,
            VerificationFailure.Code.ADMISSION_RECONCILIATION_FAILED,
            "Risk reconciliation failed with gRPC status "
                + Status.fromThrowable(failure).getCode(),
            failure);
      }

      RiskAdmissionSemantics.validateCommandId(scenario, response);
      switch (response.getStatus()) {
        case ADMISSION_OUTCOME_STATUS_NOT_FOUND ->
            throw new VerificationFailure(
                VerificationFailure.Stage.ADMISSION_RECONCILIATION,
                VerificationFailure.Code.ADMISSION_NOT_FOUND,
                "Risk returned NOT_FOUND for the durable admission after UNAVAILABLE submission");
        case ADMISSION_OUTCOME_STATUS_PENDING -> {
          RiskAdmissionSemantics.validateDurableIdentity(scenario, response);
          pendingObserved = true;
          waitForNextLookup(deadline);
        }
        case ADMISSION_OUTCOME_STATUS_ACCEPTED -> {
          RiskAdmissionSemantics.validateDurableIdentity(scenario, response);
          return acceptedObservation(
              scenario,
              AdmissionPath.RECOVERED_ACCEPTED,
              submission,
              pendingObserved,
              attempts,
              "ACCEPTED");
        }
        case ADMISSION_OUTCOME_STATUS_REJECTED ->
            throw RiskAdmissionSemantics.rejected(
                VerificationFailure.Stage.ADMISSION_RECONCILIATION,
                response.getReasonCode(),
                response.getReasonDetail());
        case ADMISSION_OUTCOME_STATUS_UNSPECIFIED, UNRECOGNIZED ->
            throw RiskAdmissionSemantics.unspecifiedOutcome();
        default -> throw RiskAdmissionSemantics.unspecifiedOutcome();
      }
    }
  }

  private void waitForNextLookup(VerificationDeadline deadline) {
    final Duration remaining =
        deadline.requireRemaining(
            VerificationFailure.Stage.ADMISSION_RECONCILIATION,
            VerificationFailure.Code.ADMISSION_REMAINED_PENDING,
            "Risk admission remained pending until the verifier deadline");
    try {
      sleeper.sleep(min(remaining, POLL_INTERVAL));
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new VerificationFailure(
          VerificationFailure.Stage.ADMISSION_RECONCILIATION,
          VerificationFailure.Code.ADMISSION_RECONCILIATION_FAILED,
          "Risk reconciliation wait was interrupted",
          interrupted);
    }
  }

  private AdmissionObservation acceptedObservation(
      Scenario scenario,
      AdmissionPath path,
      SubmissionObservation submission,
      boolean pendingObserved,
      int attempts,
      String terminalStatus) {
    return new AdmissionObservation(
        path,
        scenario.command().commandId().toString(),
        scenario.command().orderId().toString(),
        scenario.run().accountId().toString(),
        pendingObserved,
        attempts,
        Duration.ofNanos(nanoTime.getAsLong() - submission.startedAtNanos()).toMillis(),
        submission.grpcCode(),
        terminalStatus);
  }

  private static Duration min(Duration left, Duration right) {
    return left.compareTo(right) <= 0 ? left : right;
  }

  private static void sleepThread(Duration duration) throws InterruptedException {
    TimeUnit.NANOSECONDS.sleep(duration.toNanos());
  }

  enum AdmissionPath {
    SYNCHRONOUS_ACCEPTED,
    RECOVERED_ACCEPTED
  }

  record SubmissionObservation(
      Status.Code grpcCode,
      Optional<OrderAdmissionResponse> response,
      long startedAtNanos,
      long completedAtNanos) {
    SubmissionObservation {
      Objects.requireNonNull(grpcCode, "gRPC code is required");
      Objects.requireNonNull(response, "response optional is required");
      if (completedAtNanos < startedAtNanos) {
        throw new IllegalArgumentException("submission completion precedes its start");
      }
    }

    long elapsedMillis() {
      return Duration.ofNanos(completedAtNanos - startedAtNanos).toMillis();
    }
  }

  record AdmissionObservation(
      AdmissionPath path,
      String commandId,
      String orderId,
      String accountId,
      boolean pendingObserved,
      int reconciliationAttempts,
      long elapsedMillis,
      Status.Code initialGrpcCode,
      String terminalStatus) {
    AdmissionObservation {
      Objects.requireNonNull(path, "admission path is required");
      Objects.requireNonNull(commandId, "command id is required");
      Objects.requireNonNull(orderId, "order id is required");
      Objects.requireNonNull(accountId, "account id is required");
      Objects.requireNonNull(initialGrpcCode, "initial gRPC code is required");
      Objects.requireNonNull(terminalStatus, "terminal status is required");
      if (reconciliationAttempts < 0 || elapsedMillis < 0) {
        throw new IllegalArgumentException("admission observation counters must be non-negative");
      }
    }
  }

  @FunctionalInterface
  interface Sleeper {
    void sleep(Duration duration) throws InterruptedException;
  }

  interface RiskGateway {
    OrderAdmissionResponse submit(NewOrderCommand request, Duration timeout);

    GetAdmissionOutcomeResponse lookup(String commandId, Duration timeout);
  }

  private static final class GrpcRiskGateway implements RiskGateway {
    private final OrderAdmissionServiceGrpc.OrderAdmissionServiceBlockingStub stub;

    private GrpcRiskGateway(ManagedChannel channel) {
      stub =
          OrderAdmissionServiceGrpc.newBlockingStub(
              Objects.requireNonNull(channel, "Risk channel is required"));
    }

    @Override
    public OrderAdmissionResponse submit(NewOrderCommand request, Duration timeout) {
      return stub.withDeadlineAfter(timeout.toNanos(), TimeUnit.NANOSECONDS)
          .submitNewOrder(request);
    }

    @Override
    public GetAdmissionOutcomeResponse lookup(String commandId, Duration timeout) {
      final GetAdmissionOutcomeRequest request =
          GetAdmissionOutcomeRequest.newBuilder().setCommandId(commandId).build();
      return stub.withDeadlineAfter(timeout.toNanos(), TimeUnit.NANOSECONDS)
          .getAdmissionOutcome(request);
    }
  }
}
