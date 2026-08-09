package com.simplematch.quickfixgateway.risk;

import com.simplematch.contracts.risk.v2.GetAdmissionOutcomeRequest;
import com.simplematch.contracts.risk.v2.GetAdmissionOutcomeResponse;
import com.simplematch.contracts.risk.v2.OrderAdmissionServiceGrpc;
import io.grpc.ManagedChannel;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** Reads authoritative Risk admission snapshots through the v2 gRPC contract. */
public final class GrpcRiskReconciliationClient implements RiskReconciliationClient {
  private final OrderAdmissionServiceGrpc.OrderAdmissionServiceBlockingStub blockingStub;
  private final long deadlineMillis;

  /** Creates a reconciliation client whose lookup uses the supplied channel and deadline. */
  public GrpcRiskReconciliationClient(ManagedChannel managedChannel, long deadlineMillis) {
    this.blockingStub = OrderAdmissionServiceGrpc.newBlockingStub(managedChannel);
    this.deadlineMillis = deadlineMillis;
  }

  @Override
  public RiskReconciliationResult lookup(String commandId) {
    if (commandId == null || commandId.isBlank()) {
      throw new IllegalArgumentException("command_id must not be blank");
    }
    final GetAdmissionOutcomeResponse response =
        blockingStub
            .withDeadlineAfter(deadlineMillis, TimeUnit.MILLISECONDS)
            .getAdmissionOutcome(
                GetAdmissionOutcomeRequest.newBuilder().setCommandId(commandId).build());
    return toResult(response);
  }

  private RiskReconciliationResult toResult(GetAdmissionOutcomeResponse response) {
    Objects.requireNonNull(response, "response");
    final RiskReconciliationResult.Outcome outcome =
        switch (response.getStatus()) {
          case ADMISSION_OUTCOME_STATUS_NOT_FOUND -> RiskReconciliationResult.Outcome.NOT_FOUND;
          case ADMISSION_OUTCOME_STATUS_PENDING -> RiskReconciliationResult.Outcome.PENDING;
          case ADMISSION_OUTCOME_STATUS_ACCEPTED -> RiskReconciliationResult.Outcome.ACCEPTED;
          case ADMISSION_OUTCOME_STATUS_REJECTED -> RiskReconciliationResult.Outcome.REJECTED;
          case ADMISSION_OUTCOME_STATUS_UNSPECIFIED, UNRECOGNIZED ->
              throw new IllegalStateException(
                  "risk-service returned an unspecified reconciliation outcome");
        };
    return new RiskReconciliationResult(
        response.getCommandId(),
        outcome,
        response.getOrderId(),
        response.getReasonCode(),
        response.getReasonDetail());
  }
}
