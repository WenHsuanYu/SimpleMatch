package com.simplematch.riskservice.grpc;

import com.simplematch.contracts.risk.v2.AdmissionOutcomeStatus;
import com.simplematch.contracts.risk.v2.GetAdmissionOutcomeRequest;
import com.simplematch.contracts.risk.v2.GetAdmissionOutcomeResponse;
import com.simplematch.riskservice.admission.AdmissionResult;
import com.simplematch.riskservice.admission.AdmissionState;
import com.simplematch.riskservice.admission.OrderAdmissionApplicationService;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.Objects;
import java.util.UUID;

/** Projects durable Risk admission state onto the reconciliation gRPC response. */
final class AdmissionOutcomeGrpcResponder {
  private final OrderAdmissionApplicationService admissions;

  AdmissionOutcomeGrpcResponder(OrderAdmissionApplicationService admissions) {
    this.admissions = Objects.requireNonNull(admissions, "admissions");
  }

  void respond(
      GetAdmissionOutcomeRequest request,
      StreamObserver<GetAdmissionOutcomeResponse> responseObserver) {
    final UUID commandId;
    try {
      commandId = UUID.fromString(request.getCommandId());
    } catch (IllegalArgumentException invalid) {
      responseObserver.onError(
          Status.INVALID_ARGUMENT
              .withDescription("command_id must be a UUID")
              .asRuntimeException());
      return;
    }

    final AdmissionResult result = admissions.findOutcome(commandId).orElse(null);
    responseObserver.onNext(
        result == null ? notFoundOutcome(commandId) : admissionOutcome(result));
    responseObserver.onCompleted();
  }

  private GetAdmissionOutcomeResponse notFoundOutcome(UUID commandId) {
    return GetAdmissionOutcomeResponse.newBuilder()
        .setCommandId(commandId.toString())
        .setStatus(AdmissionOutcomeStatus.ADMISSION_OUTCOME_STATUS_NOT_FOUND)
        .build();
  }

  private GetAdmissionOutcomeResponse admissionOutcome(AdmissionResult result) {
    return GetAdmissionOutcomeResponse.newBuilder()
        .setCommandId(result.commandId().toString())
        .setStatus(outcomeStatus(result.state()))
        .setOrderId(result.orderId().toString())
        .setAccountId(result.accountId().toString())
        .setReasonCode(result.reasonCode())
        .setReasonDetail(result.reasonDetail())
        .build();
  }

  private AdmissionOutcomeStatus outcomeStatus(AdmissionState state) {
    return switch (state) {
      case PENDING -> AdmissionOutcomeStatus.ADMISSION_OUTCOME_STATUS_PENDING;
      case ACCEPTED -> AdmissionOutcomeStatus.ADMISSION_OUTCOME_STATUS_ACCEPTED;
      case REJECTED -> AdmissionOutcomeStatus.ADMISSION_OUTCOME_STATUS_REJECTED;
    };
  }
}
