package com.simplematch.riskservice.grpc;

import com.simplematch.contracts.common.v2.EventMetadata;
import com.simplematch.contracts.common.v2.VenueInstrument;
import com.simplematch.contracts.orders.v2.CancelOrderCommand;
import com.simplematch.contracts.orders.v2.NewOrderCommand;
import com.simplematch.contracts.orders.v2.OrderAdmissionAccepted;
import com.simplematch.contracts.orders.v2.OrderAdmissionRejected;
import com.simplematch.contracts.risk.v2.OrderAdmissionResponse;
import com.simplematch.contracts.risk.v2.OrderAdmissionServiceGrpc;
import com.simplematch.riskservice.admission.AdmissionConflictException;
import com.simplematch.riskservice.admission.AdmissionFailure;
import com.simplematch.riskservice.admission.AdmissionResult;
import com.simplematch.riskservice.admission.AdmissionState;
import com.simplematch.riskservice.admission.AdmissionUnavailableException;
import com.simplematch.riskservice.admission.AdmissionValidationException;
import com.simplematch.riskservice.admission.OrderAdmissionApplicationService;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Bridges the durable v2 order-admission seam to protobuf responses.
 *
 * <p>The adapter maps terminal admission outcomes to v2 protobuf responses.
 */
@Service
public final class OrderAdmissionGrpcService
    extends OrderAdmissionServiceGrpc.OrderAdmissionServiceImplBase {
  private final OrderAdmissionApplicationService admissions;
  private final Clock clock;

  /** Creates the v2 adapter with the durable admission application service. */
  public OrderAdmissionGrpcService(OrderAdmissionApplicationService admissions, Clock clock) {
    this.admissions = admissions;
    this.clock = clock;
  }

  /** Submits a v2 new order through the pending-account-finalize saga. */
  @Override
  public void submitNewOrder(
      NewOrderCommand request, StreamObserver<OrderAdmissionResponse> responseObserver) {
    try {
      responseObserver.onNext(toResponse(admissions.admit(request)));
      responseObserver.onCompleted();
    } catch (AdmissionValidationException invalid) {
      responseObserver.onNext(rejection(request, invalid.failure()));
      responseObserver.onCompleted();
    } catch (AdmissionConflictException conflict) {
      responseObserver.onError(
          Status.ALREADY_EXISTS.withDescription(conflict.getMessage()).asRuntimeException());
    } catch (AdmissionUnavailableException unavailable) {
      responseObserver.onError(
          Status.UNAVAILABLE.withDescription(unavailable.getMessage()).asRuntimeException());
    } catch (RuntimeException failure) {
      responseObserver.onError(
          Status.INTERNAL.withDescription("failed to admit order").asRuntimeException());
    }
  }

  /** Admits cancellation through the same durable journal and terminal outbox. */
  @Override
  public void submitCancel(
      CancelOrderCommand request, StreamObserver<OrderAdmissionResponse> responseObserver) {
    try {
      responseObserver.onNext(toResponse(admissions.admitCancel(request)));
      responseObserver.onCompleted();
    } catch (AdmissionValidationException invalid) {
      responseObserver.onNext(rejection(request, invalid.failure()));
      responseObserver.onCompleted();
    } catch (AdmissionConflictException conflict) {
      responseObserver.onError(
          Status.ALREADY_EXISTS.withDescription(conflict.getMessage()).asRuntimeException());
    } catch (RuntimeException failure) {
      responseObserver.onError(
          Status.INTERNAL.withDescription("failed to admit cancel").asRuntimeException());
    }
  }

  private OrderAdmissionResponse toResponse(AdmissionResult result) {
    final EventMetadata metadata = metadata(result.commandId());
    if (result.state() == AdmissionState.ACCEPTED) {
      return OrderAdmissionResponse.newBuilder()
          .setAccepted(
              OrderAdmissionAccepted.newBuilder()
                  .setMetadata(metadata)
                  .setCommandId(result.commandId().toString())
                  .setOrderId(result.orderId().toString())
                  .setAccountId(result.accountId().toString())
                  .setRoutingSnapshotId(result.routingSnapshotId())
                  .setRoutingPolicyId(
                      result.routingPolicyId() == null ? "" : result.routingPolicyId().toString())
                  .setRoutingPartition(
                      result.routingPartition() == null ? 0 : result.routingPartition())
                  .build())
          .build();
    }
    return rejection(RejectedAdmission.from(result));
  }

  private OrderAdmissionResponse rejection(NewOrderCommand request, AdmissionFailure failure) {
    return rejection(RejectedAdmission.from(request, failure));
  }

  private OrderAdmissionResponse rejection(CancelOrderCommand request, AdmissionFailure failure) {
    return rejection(RejectedAdmission.from(request, failure));
  }

  private OrderAdmissionResponse rejection(RejectedAdmission rejection) {
    final EventMetadata metadata = metadata(rejection.commandId());
    return OrderAdmissionResponse.newBuilder()
        .setRejected(
            OrderAdmissionRejected.newBuilder()
                .setMetadata(metadata)
                .setCommandId(rejection.commandId())
                .setOrderId(rejection.orderId())
                .setAccountId(rejection.accountId())
                .setInstrument(rejection.instrument())
                .setReason(
                    com.simplematch.contracts.orders.v2.AdmissionRejectReason
                        .ADMISSION_REJECT_REASON_INVALID_COMMAND)
                .setReasonDetail(
                    rejection.failure().reasonCode().value()
                        + ": "
                        + rejection.failure().detail().value())
                .build())
        .build();
  }

  private EventMetadata metadata(UUID commandId) {
    return metadata(commandId.toString());
  }

  private EventMetadata metadata(String correlationId) {
    return EventMetadata.newBuilder()
        .setSchemaVersion("v2")
        .setEventId(correlationId)
        .setCreatedAtUnixMs(clock.millis())
        .setSourceService("risk-service")
        .setCorrelationId(correlationId)
        .build();
  }

  private record RejectedAdmission(
      String commandId,
      String orderId,
      String accountId,
      VenueInstrument instrument,
      AdmissionFailure failure) {
    private static RejectedAdmission from(AdmissionResult result) {
      return new RejectedAdmission(
          result.commandId().toString(),
          result.orderId().toString(),
          result.accountId().toString(),
          VenueInstrument.getDefaultInstance(),
          new AdmissionFailure(
              new AdmissionFailure.ReasonCode(result.reasonCode()),
              new AdmissionFailure.Detail(result.reasonDetail())));
    }

    private static RejectedAdmission from(NewOrderCommand request, AdmissionFailure failure) {
      return new RejectedAdmission(
          request.getCommandId(),
          request.getOrderId(),
          request.getAccountId(),
          request.getInstrument(),
          failure);
    }

    private static RejectedAdmission from(CancelOrderCommand request, AdmissionFailure failure) {
      return new RejectedAdmission(
          request.getCommandId(),
          request.getOrderId(),
          request.getAccountId(),
          request.getInstrument(),
          failure);
    }
  }
}
