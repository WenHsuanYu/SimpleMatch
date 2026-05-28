package com.simplematch.riskservice.grpc;

import com.simplematch.contracts.orders.v1.CommandType;
import com.simplematch.contracts.orders.v1.OrderCommand;
import com.simplematch.contracts.risk.v1.CancelOrderRequest;
import com.simplematch.contracts.risk.v1.CancelOrderResponse;
import com.simplematch.contracts.risk.v1.RiskServiceGrpc;
import com.simplematch.contracts.risk.v1.SubmitOrderRequest;
import com.simplematch.contracts.risk.v1.SubmitOrderResponse;
import com.simplematch.riskservice.submission.ResolvedSubmissionCommand;
import com.simplematch.riskservice.submission.SubmissionResult;
import com.simplematch.riskservice.submission.SubmissionService;
import io.grpc.stub.StreamObserver;
import org.springframework.stereotype.Service;

/**
 * gRPC adapter for the risk service.
 *
 * <p>This class keeps the transport-layer responsibilities small: it normalizes incoming order
 * commands, delegates persistence to {@link SubmissionService}, and translates the stored result
 * back into the protobuf response expected by clients.
 */
@Service
public class RiskGrpcService extends RiskServiceGrpc.RiskServiceImplBase {
  private static final GrpcSubmissionCommandMapper COMMAND_MAPPER = new GrpcSubmissionCommandMapper();

  private final SubmissionService submissionService;

  /**
   * Creates a risk gRPC service that delegates submissions to the provided persistence service.
   *
   * @param submissionService the service responsible for persisting order submissions
   */
  public RiskGrpcService(SubmissionService submissionService) {
    this.submissionService = submissionService;
  }

  /**
   * Accepts a new-order request, normalizes the command payload, persists the submission, and
   * returns the resulting acknowledgement to the caller.
   *
   * @param request the inbound gRPC request
   * @param responseObserver the gRPC observer used to stream the response
   */
  @Override
  public void submitOrder(SubmitOrderRequest request, StreamObserver<SubmitOrderResponse> responseObserver) {
    final SubmissionResult submission = submissionService.persist(
      toResolvedSubmissionCommand(request.getCommand(), CommandType.COMMAND_TYPE_NEW));
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

  /**
   * Accepts a cancel-order request, normalizes the command payload, persists the submission, and
   * returns the resulting acknowledgement to the caller.
   *
   * @param request the inbound gRPC request
   * @param responseObserver the gRPC observer used to stream the response
   */
  @Override
  public void cancelOrder(CancelOrderRequest request, StreamObserver<CancelOrderResponse> responseObserver) {
    final SubmissionResult submission = submissionService.persist(
      toResolvedSubmissionCommand(request.getCommand(), CommandType.COMMAND_TYPE_CANCEL));
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

  /**
   * Ensures the command has the expected type before it is persisted.
   *
   * <p>The protobuf default instance and missing payloads are both treated as empty commands, and
   * any mismatched command type is rewritten to the type required by the current RPC.
   *
   * @param command the incoming protobuf command, which may be absent or use the wrong type
   * @param expectedType the command type required by the RPC being handled
   * @return a command whose type matches the request being processed
   */
  private ResolvedSubmissionCommand toResolvedSubmissionCommand(OrderCommand command, CommandType expectedType) {
    return COMMAND_MAPPER.map(command, expectedType);
  }
}