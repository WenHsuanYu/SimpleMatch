package com.simplematch.riskservice.grpc;

import com.simplematch.contracts.common.v2.EventMetadata;
import com.simplematch.contracts.common.v2.VenueInstrument;
import com.simplematch.contracts.orders.v2.NewOrderCommand;
import com.simplematch.contracts.orders.v2.OrderAdmissionAccepted;
import com.simplematch.contracts.orders.v2.OrderAdmissionRejected;
import com.simplematch.contracts.risk.v2.OrderAdmissionResponse;
import com.simplematch.contracts.risk.v2.OrderAdmissionServiceGrpc;
import com.simplematch.riskservice.admission.AdmissionConflictException;
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

/** gRPC adapter for the durable v2 order-admission seam. */
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
      responseObserver.onNext(rejection(request, invalid.reasonCode(), invalid.detail()));
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
      com.simplematch.contracts.orders.v2.CancelOrderCommand request,
      StreamObserver<OrderAdmissionResponse> responseObserver) {
    try {
      responseObserver.onNext(toResponse(admissions.admitCancel(request)));
      responseObserver.onCompleted();
    } catch (AdmissionValidationException invalid) {
      responseObserver.onNext(
          rejection(
              request.getCommandId(),
              request.getOrderId(),
              request.getAccountId(),
              request.getInstrument(),
              invalid.reasonCode(),
              invalid.detail()));
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
                  .setRoutingPartition(
                      result.routingPartition() == null ? 0 : result.routingPartition())
                  .build())
          .build();
    }
    return rejection(
        result.commandId().toString(),
        result.orderId().toString(),
        result.accountId().toString(),
        VenueInstrument.getDefaultInstance(),
        result.reasonCode(),
        result.reasonDetail());
  }

  private OrderAdmissionResponse rejection(
      NewOrderCommand request, String reasonCode, String detail) {
    return rejection(
        request.getCommandId(),
        request.getOrderId(),
        request.getAccountId(),
        request.getInstrument(),
        reasonCode,
        detail);
  }

  private OrderAdmissionResponse rejection(
      String commandId,
      String orderId,
      String accountId,
      VenueInstrument instrument,
      String reasonCode,
      String detail) {
    return rejection(
        commandId, orderId, accountId, instrument, reasonCode, detail, metadata(commandId));
  }

  private OrderAdmissionResponse rejection(
      String commandId,
      String orderId,
      String accountId,
      VenueInstrument instrument,
      String reasonCode,
      String detail,
      EventMetadata metadata) {
    return OrderAdmissionResponse.newBuilder()
        .setRejected(
            OrderAdmissionRejected.newBuilder()
                .setMetadata(metadata)
                .setCommandId(commandId)
                .setOrderId(orderId)
                .setAccountId(accountId)
                .setInstrument(instrument)
                .setReason(
                    com.simplematch.contracts.orders.v2.AdmissionRejectReason
                        .ADMISSION_REJECT_REASON_INVALID_COMMAND)
                .setReasonDetail(reasonCode + ": " + detail)
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
}
