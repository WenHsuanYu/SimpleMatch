package com.simplematch.quickfixgateway.risk;

import com.simplematch.contracts.orders.v1.OrderCommand;
import com.simplematch.contracts.risk.v2.OrderAdmissionResponse;
import com.simplematch.contracts.risk.v2.OrderAdmissionServiceGrpc;
import com.simplematch.contracts.v2.DomainValidationException;
import io.grpc.ManagedChannel;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** Submits gateway commands through Risk's production v2 durable-admission contract. */
public final class GrpcRiskSubmissionClient implements RiskSubmissionClient {
  private final OrderAdmissionServiceGrpc.OrderAdmissionServiceBlockingStub blockingStub;
  private final RiskV2CommandAdapter commandAdapter;
  private final long deadlineMillis;

  /** Creates a v2 admission client using the supplied FIX-to-Risk identity adapter. */
  public GrpcRiskSubmissionClient(
      ManagedChannel managedChannel,
      long deadlineMillis,
      RiskV2CommandAdapter commandAdapter) {
    blockingStub = OrderAdmissionServiceGrpc.newBlockingStub(managedChannel);
    this.deadlineMillis = deadlineMillis;
    this.commandAdapter = Objects.requireNonNull(commandAdapter, "commandAdapter");
  }

  @Override
  public RiskSubmissionResult submitNewOrder(OrderCommand command) {
    try {
      final OrderAdmissionResponse response =
          blockingStub
              .withDeadlineAfter(deadlineMillis, TimeUnit.MILLISECONDS)
              .submitNewOrder(commandAdapter.toNewOrder(command));
      return toResult(response, command.getOrderId());
    } catch (DomainValidationException invalid) {
      return invalidCommand(command, invalid);
    }
  }

  @Override
  public RiskSubmissionResult submitCancel(OrderCommand command) {
    try {
      final OrderAdmissionResponse response =
          blockingStub
              .withDeadlineAfter(deadlineMillis, TimeUnit.MILLISECONDS)
              .submitCancel(commandAdapter.toCancelOrder(command));
      return toResult(response, command.getOrderId());
    } catch (DomainValidationException invalid) {
      return invalidCommand(command, invalid);
    }
  }

  private RiskSubmissionResult toResult(OrderAdmissionResponse response, String clientOrderId) {
    if (response.hasAccepted()) {
      return new RiskSubmissionResult(
          clientOrderId, RiskSubmissionResult.Outcome.ACCEPTED, "", "");
    }
    if (response.hasRejected()) {
      final var rejected = response.getRejected();
      return new RiskSubmissionResult(
          clientOrderId,
          RiskSubmissionResult.Outcome.REJECTED,
          rejected.getReason().name(),
          rejected.getReasonDetail());
    }
    throw new IllegalStateException("risk-service returned no v2 admission outcome");
  }

  private RiskSubmissionResult invalidCommand(
      OrderCommand command, DomainValidationException invalid) {
    return new RiskSubmissionResult(
        command.getOrderId(),
        RiskSubmissionResult.Outcome.REJECTED,
        "INVALID_COMMAND",
        invalid.getMessage());
  }
}
