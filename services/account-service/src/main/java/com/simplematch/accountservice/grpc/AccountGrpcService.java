package com.simplematch.accountservice.grpc;

import com.simplematch.accountservice.authority.AccountLimit;
import com.simplematch.accountservice.authority.AccountPosition;
import com.simplematch.accountservice.reservation.AccountReservationApplicationService;
import com.simplematch.accountservice.reservation.ApplyFillOperation;
import com.simplematch.accountservice.reservation.ExecutionFill;
import com.simplematch.accountservice.reservation.ReleaseReservationOperation;
import com.simplematch.accountservice.reservation.ReservationIdentity;
import com.simplematch.accountservice.reservation.ReservationRecord;
import com.simplematch.accountservice.reservation.ReservationRequestIdentity;
import com.simplematch.accountservice.reservation.ReservationTerms;
import com.simplematch.accountservice.reservation.ReserveOperation;
import com.simplematch.contracts.account.v1.AccountServiceGrpc;
import com.simplematch.contracts.account.v1.ApplyFillRequest;
import com.simplematch.contracts.account.v1.ApplyFillResponse;
import com.simplematch.contracts.account.v1.GetLimitsRequest;
import com.simplematch.contracts.account.v1.GetLimitsResponse;
import com.simplematch.contracts.account.v1.GetPositionsRequest;
import com.simplematch.contracts.account.v1.GetPositionsResponse;
import com.simplematch.contracts.account.v1.PositionSnapshot;
import com.simplematch.contracts.account.v1.ReleaseReservationRequest;
import com.simplematch.contracts.account.v1.ReleaseReservationResponse;
import com.simplematch.contracts.account.v1.ReserveRequest;
import com.simplematch.contracts.account.v1.ReserveResponse;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Handles account-service control-plane operations through gRPC.
 *
 * <p>Write RPCs in this service intentionally use {@code request_id} as the synchronous name
 * for the same operation identifier that enters the trading flow as {@code command_id} on {@code
 * OrderCommand}. All reservation lifecycle paths delegate to the account authority application
 * service.
 */
@Service
@RequiredArgsConstructor
public class AccountGrpcService extends AccountServiceGrpc.AccountServiceImplBase {
  private static final int MAX_PERSISTED_IDENTIFIER_LENGTH = 255;
  @NonNull private final AccountReservationApplicationService reservationService;

  @Override
  public void getLimits(
      GetLimitsRequest request, StreamObserver<GetLimitsResponse> responseObserver) {
    try {
      final AccountLimit limit = reservationService.getLimits(request.getAccountId());
      responseObserver.onNext(
          GetLimitsResponse.newBuilder()
              .setAccountId(limit.accountId())
              .setCurrency(limit.currency())
              .setAvailableNotional(limit.availableNotional().toPlainString())
              .setReservedNotional(limit.reservedNotional().toPlainString())
              .setUtilizedNotional(limit.utilizedNotional().toPlainString())
              .build());
      responseObserver.onCompleted();
    } catch (RuntimeException failure) {
      responseObserver.onError(
          Status.INTERNAL.withDescription("failed to read account limits").asRuntimeException());
    }
  }

  @Override
  public void getPositions(
      GetPositionsRequest request, StreamObserver<GetPositionsResponse> responseObserver) {
    try {
      final GetPositionsResponse.Builder response = GetPositionsResponse.newBuilder();
      for (AccountPosition position : reservationService.getPositions(request.getAccountId())) {
        response.addPositions(
            PositionSnapshot.newBuilder()
                .setAccountId(position.accountId())
                .setSymbol(position.symbol())
                .setLongQty(position.longQuantity().toPlainString())
                .setShortQty(position.shortQuantity().toPlainString())
                .build());
      }
      responseObserver.onNext(response.build());
      responseObserver.onCompleted();
    } catch (RuntimeException failure) {
      responseObserver.onError(
          Status.INTERNAL.withDescription("failed to read account positions").asRuntimeException());
    }
  }

  /**
   * Persists or replays the reservation identified by {@code request_id}.
   *
   * <p>{@code request_id} is the control-plane name for the upstream operation identifier currently
   * carried as {@code command_id} on order events.
   */
  @Override
  public void reserve(ReserveRequest request, StreamObserver<ReserveResponse> responseObserver) {
    try {
      final ReservationRecord reservation = reservationService.reserve(toReserveOperation(request));
      responseObserver.onNext(
          ReserveResponse.newBuilder()
              .setRequestId(reservation.requestId())
              .setOrderId(reservation.orderId())
              .setStatus(reservation.status())
              .setReservationId(reservation.reservationId())
              .setReasonCode(reservation.reasonCode())
              .setReasonText(reservation.reasonText())
              .build());
      responseObserver.onCompleted();
    } catch (IllegalArgumentException illegalArgumentException) {
      responseObserver.onError(
          Status.INVALID_ARGUMENT
              .withDescription(illegalArgumentException.getMessage())
              .asRuntimeException());
    } catch (RuntimeException runtimeException) {
      responseObserver.onError(
          Status.INTERNAL.withDescription("failed to persist reservation").asRuntimeException());
    }
  }

  /** Releases a reservation and returns its terminal lifecycle state. */
  @Override
  public void releaseReservation(
      ReleaseReservationRequest request,
      StreamObserver<ReleaseReservationResponse> responseObserver) {
    try {
      final ReservationRecord reservation =
          reservationService.release(
              new ReleaseReservationOperation(
                  new ReservationIdentity(
                      new ReservationIdentity.RequestId(request.getRequestId()),
                      new ReservationIdentity.ReservationId(request.getReservationId()),
                      new ReservationIdentity.OrderId(request.getOrderId())),
                  new ReleaseReservationOperation.ReleaseReason(request.getReasonCode())));
      responseObserver.onNext(
          ReleaseReservationResponse.newBuilder()
              .setRequestId(reservation.requestId())
              .setReservationId(reservation.reservationId())
              .setStatus(reservation.status())
              .build());
      responseObserver.onCompleted();
    } catch (IllegalArgumentException invalid) {
      responseObserver.onError(
          Status.INVALID_ARGUMENT.withDescription(invalid.getMessage()).asRuntimeException());
    } catch (RuntimeException failure) {
      responseObserver.onError(
          Status.INTERNAL.withDescription("failed to release reservation").asRuntimeException());
    }
  }

  /** Applies one execution fill, deduplicated by execution identifier. */
  @Override
  public void applyFill(
      ApplyFillRequest request, StreamObserver<ApplyFillResponse> responseObserver) {
    try {
      final ReservationRecord reservation =
          reservationService.applyFill(
              new ApplyFillOperation(
                  new ReservationIdentity(
                      new ReservationIdentity.RequestId(request.getRequestId()),
                      new ReservationIdentity.ReservationId(request.getReservationId()),
                      new ReservationIdentity.OrderId(request.getOrderId())),
                  new ExecutionFill(
                      new ExecutionFill.ExecutionId(request.getExecId()),
                      ExecutionFill.AggregateSequence.absent(),
                      new ExecutionFill.FillQuantity(
                          parsePositiveDecimal(request.getFillQty(), "fill_qty")),
                      new ExecutionFill.FillPrice(
                          parsePositiveDecimal(request.getFillPx(), "fill_px")))));
      responseObserver.onNext(
          ApplyFillResponse.newBuilder()
              .setRequestId(reservation.requestId())
              .setReservationId(reservation.reservationId())
              .setStatus(reservation.status())
              .build());
      responseObserver.onCompleted();
    } catch (IllegalArgumentException invalid) {
      responseObserver.onError(
          Status.INVALID_ARGUMENT.withDescription(invalid.getMessage()).asRuntimeException());
    } catch (RuntimeException failure) {
      responseObserver.onError(
          Status.INTERNAL.withDescription("failed to apply fill").asRuntimeException());
    }
  }

  private ReserveOperation toReserveOperation(ReserveRequest request) {
    final ReserveOperation operation =
        new ReserveOperation(
            new ReservationRequestIdentity(
                new ReservationRequestIdentity.RequestId(request.getRequestId()),
                new ReservationRequestIdentity.OrderId(request.getOrderId()),
                new ReservationRequestIdentity.AccountId(request.getAccountId())),
            new ReservationTerms(
                new ReservationTerms.InstrumentSymbol(request.getSymbol()),
                request.getSide(),
                new ReservationTerms.ReservationQuantity(
                    parsePositiveDecimal(request.getQuantity(), "quantity")),
                new ReservationTerms.LimitPrice(
                    parseOptionalPositiveDecimal(request.getLimitPrice(), "limit_price"))));
    validateIngressIdentifiers(operation.requestId(), operation.orderId());
    return operation;
  }

  private void validateIngressIdentifiers(String requestId, String orderId) {
    validateBoundedIdentifier(requestId, "request_id");
    validateUuidIdentifier(requestId, "request_id");
    validateBoundedIdentifier(orderId, "order_id");
  }

  private void validateUuidIdentifier(String value, String fieldName) {
    try {
      UUID.fromString(value);
    } catch (IllegalArgumentException illegalArgumentException) {
      throw new IllegalArgumentException(fieldName + " must be a UUID", illegalArgumentException);
    }
  }

  private void validateBoundedIdentifier(String value, String fieldName) {
    if (value.length() > MAX_PERSISTED_IDENTIFIER_LENGTH) {
      throw new IllegalArgumentException(
          fieldName + " must be <= " + MAX_PERSISTED_IDENTIFIER_LENGTH + " characters");
    }
  }

  private BigDecimal parsePositiveDecimal(String rawValue, String fieldName) {
    final BigDecimal parsed = parseDecimal(rawValue, fieldName);
    if (parsed.signum() <= 0) {
      throw new IllegalArgumentException(fieldName + " must be positive");
    }
    return parsed;
  }

  private BigDecimal parseOptionalPositiveDecimal(String rawValue, String fieldName) {
    if (rawValue == null || rawValue.isBlank()) {
      return null;
    }
    final BigDecimal parsed = parseDecimal(rawValue, fieldName);
    if (parsed.signum() <= 0) {
      throw new IllegalArgumentException(fieldName + " must be positive when provided");
    }
    return parsed;
  }

  private BigDecimal parseDecimal(String rawValue, String fieldName) {
    if (rawValue == null || rawValue.isBlank()) {
      throw new IllegalArgumentException(fieldName + " must not be blank");
    }
    try {
      return new BigDecimal(rawValue);
    } catch (NumberFormatException numberFormatException) {
      throw new IllegalArgumentException(
          fieldName + " must be a valid decimal", numberFormatException);
    }
  }
}
