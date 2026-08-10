package com.simplematch.quickfixgateway.risk;

import com.simplematch.contracts.orders.v2.CancelOrderCommand;
import com.simplematch.contracts.orders.v2.NewOrderCommand;
import com.simplematch.contracts.risk.v2.OrderAdmissionResponse;
import com.simplematch.contracts.risk.v2.OrderAdmissionServiceGrpc;
import io.grpc.ManagedChannel;
import java.util.concurrent.TimeUnit;

/** Submits typed Gateway commands through Risk's production v2 durable-admission contract. */
public final class GrpcRiskSubmissionClient implements RiskSubmissionClient {
  private final OrderAdmissionServiceGrpc.OrderAdmissionServiceBlockingStub blockingStub;
  private final long deadlineMillis;

  /** Creates a v2 admission client for an existing managed channel. */
  public GrpcRiskSubmissionClient(ManagedChannel managedChannel, long deadlineMillis) {
    blockingStub = OrderAdmissionServiceGrpc.newBlockingStub(managedChannel);
    this.deadlineMillis = deadlineMillis;
  }

  @Override
  public RiskSubmissionResult submitNewOrder(NewOrderCommand command) {
    final OrderAdmissionResponse response =
        blockingStub
            .withDeadlineAfter(deadlineMillis, TimeUnit.MILLISECONDS)
            .submitNewOrder(command);
    return toResult(response, command.getOrderId());
  }

  @Override
  public RiskSubmissionResult submitCancel(CancelOrderCommand command) {
    final OrderAdmissionResponse response =
        blockingStub
            .withDeadlineAfter(deadlineMillis, TimeUnit.MILLISECONDS)
            .submitCancel(command);
    return toResult(response, command.getOrderId());
  }

  private RiskSubmissionResult toResult(OrderAdmissionResponse response, String orderId) {
    if (response.hasAccepted()) {
      return new RiskSubmissionResult(
          orderId, RiskSubmissionResult.Outcome.ACCEPTED, "", "");
    }
    if (response.hasRejected()) {
      final var rejected = response.getRejected();
      return new RiskSubmissionResult(
          orderId,
          RiskSubmissionResult.Outcome.REJECTED,
          rejected.getReason().name(),
          rejected.getReasonDetail());
    }
    throw new IllegalStateException("risk-service returned no v2 admission outcome");
  }
}
