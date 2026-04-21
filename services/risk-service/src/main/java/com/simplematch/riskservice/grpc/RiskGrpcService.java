package com.simplematch.riskservice.grpc;

import com.simplematch.contracts.orders.v1.CommandType;
import com.simplematch.contracts.orders.v1.OrderCommand;
import com.simplematch.contracts.risk.v1.CancelOrderRequest;
import com.simplematch.contracts.risk.v1.CancelOrderResponse;
import com.simplematch.contracts.risk.v1.RiskServiceGrpc;
import com.simplematch.contracts.risk.v1.SubmitOrderRequest;
import com.simplematch.contracts.risk.v1.SubmitOrderResponse;
import com.simplematch.riskservice.store.SubmissionStore;
import com.simplematch.riskservice.store.StoredSubmission;
import io.grpc.stub.StreamObserver;
import org.springframework.stereotype.Service;

@Service
public class RiskGrpcService extends RiskServiceGrpc.RiskServiceImplBase {
  private final SubmissionStore submissionStore;

  public RiskGrpcService(SubmissionStore submissionStore) {
    this.submissionStore = submissionStore;
  }

  @Override
  public void submitOrder(SubmitOrderRequest request, StreamObserver<SubmitOrderResponse> responseObserver) {
    final StoredSubmission submission = submissionStore.persist(normalize(request.getCommand(), CommandType.COMMAND_TYPE_NEW));
    responseObserver.onNext(SubmitOrderResponse.newBuilder()
        .setRequestId(submission.requestId())
        .setOrderId(submission.orderId())
        .setClientOrderId(submission.clientOrderId())
        .setAccepted(submission.accepted())
        .setReasonCode(submission.reasonCode())
        .setReasonText(submission.reasonText())
        .build());
    responseObserver.onCompleted();
  }

  @Override
  public void cancelOrder(CancelOrderRequest request, StreamObserver<CancelOrderResponse> responseObserver) {
    final StoredSubmission submission = submissionStore.persist(normalize(request.getCommand(), CommandType.COMMAND_TYPE_CANCEL));
    responseObserver.onNext(CancelOrderResponse.newBuilder()
        .setRequestId(submission.requestId())
        .setOrderId(submission.orderId())
        .setClientOrderId(submission.clientOrderId())
        .setOriginalClientOrderId(submission.originalClientOrderId())
        .setAccepted(submission.accepted())
        .setReasonCode(submission.reasonCode())
        .setReasonText(submission.reasonText())
        .build());
    responseObserver.onCompleted();
  }

  private OrderCommand normalize(OrderCommand command, CommandType expectedType) {
    if (command == null || OrderCommand.getDefaultInstance().equals(command)) {
      return OrderCommand.newBuilder().setCommandType(expectedType).build();
    }
    if (command.getCommandType() == expectedType) {
      return command;
    }
    return command.toBuilder().setCommandType(expectedType).build();
  }
}