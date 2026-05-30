package com.simplematch.accountservice.grpc;

import com.simplematch.accountservice.reservation.ReservationRecord;
import com.simplematch.accountservice.reservation.ReservationService;
import com.simplematch.accountservice.reservation.ReserveOperation;
import com.simplematch.contracts.account.v1.AccountServiceGrpc;
import com.simplematch.contracts.account.v1.ApplyFillRequest;
import com.simplematch.contracts.account.v1.GetLimitsRequest;
import com.simplematch.contracts.account.v1.GetPositionsRequest;
import com.simplematch.contracts.account.v1.ReserveResponse;
import com.simplematch.contracts.account.v1.ReleaseReservationRequest;
import com.simplematch.contracts.account.v1.ReserveRequest;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.math.BigDecimal;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * gRPC entry point for account-service control-plane operations.
 *
 * <p>The write RPCs in this service intentionally use {@code request_id} as the synchronous name
 * for the same operation identifier that enters the trading flow as {@code command_id} on
 * {@code OrderCommand}. The reserve path now persists and replays that identifier via
 * {@code account_reservations}; the remaining write paths stay unimplemented until the reservation
 * state machine is widened beyond the first idempotent ingress slice.
 */
@Service
public class AccountGrpcService extends AccountServiceGrpc.AccountServiceImplBase {
  private static final String MESSAGE = "account-service logic is not implemented yet";
  private final ReservationService reservationService;

  public AccountGrpcService(ReservationService reservationService) {
    this.reservationService = Objects.requireNonNull(reservationService);
  }

  @Override
  public void getLimits(GetLimitsRequest request, StreamObserver<com.simplematch.contracts.account.v1.GetLimitsResponse> responseObserver) {
    responseObserver.onError(Status.UNIMPLEMENTED.withDescription(MESSAGE).asRuntimeException());
  }

  @Override
  public void getPositions(GetPositionsRequest request, StreamObserver<com.simplematch.contracts.account.v1.GetPositionsResponse> responseObserver) {
    responseObserver.onError(Status.UNIMPLEMENTED.withDescription(MESSAGE).asRuntimeException());
  }

  /**
   * Persists or replays the reservation identified by {@code request_id}.
   *
   * <p>{@code request_id} is the control-plane name for the upstream operation identifier currently
   * carried as {@code command_id} on order events.
   */
  @Override
  public void reserve(ReserveRequest request, StreamObserver<com.simplematch.contracts.account.v1.ReserveResponse> responseObserver) {
    try {
      final ReservationRecord reservation = reservationService.reserve(toReserveOperation(request));
      responseObserver.onNext(ReserveResponse.newBuilder()
          .setRequestId(reservation.requestId())
          .setOrderId(reservation.orderId())
          .setStatus(reservation.status())
          .setReservationId(reservation.reservationId())
          .setReasonCode(reservation.reasonCode())
          .setReasonText(reservation.reasonText())
          .build());
      responseObserver.onCompleted();
    } catch (IllegalArgumentException illegalArgumentException) {
      responseObserver.onError(Status.INVALID_ARGUMENT
          .withDescription(illegalArgumentException.getMessage())
          .asRuntimeException());
    } catch (RuntimeException runtimeException) {
      responseObserver.onError(Status.INTERNAL
          .withDescription("failed to persist reservation")
          .asRuntimeException());
    }
  }

  /**
   * Rejects the unimplemented release RPC.
   *
   * <p>{@code request_id} is the control-plane name for the upstream operation identifier currently
   * carried as {@code command_id} on order events.
   */
  @Override
  public void releaseReservation(ReleaseReservationRequest request, StreamObserver<com.simplematch.contracts.account.v1.ReleaseReservationResponse> responseObserver) {
    responseObserver.onError(Status.UNIMPLEMENTED.withDescription(MESSAGE).asRuntimeException());
  }

  /**
   * Rejects the unimplemented fill-application RPC.
   *
   * <p>{@code request_id} is the control-plane name for the upstream operation identifier currently
   * carried as {@code command_id} on order events.
   */
  @Override
  public void applyFill(ApplyFillRequest request, StreamObserver<com.simplematch.contracts.account.v1.ApplyFillResponse> responseObserver) {
    responseObserver.onError(Status.UNIMPLEMENTED.withDescription(MESSAGE).asRuntimeException());
  }

  private ReserveOperation toReserveOperation(ReserveRequest request) {
    return new ReserveOperation(
        request.getRequestId(),
        request.getOrderId(),
        request.getAccountId(),
        request.getSymbol(),
        request.getSide(),
        parsePositiveDecimal(request.getQuantity(), "quantity"),
        parseOptionalPositiveDecimal(request.getLimitPrice(), "limit_price"));
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
      throw new IllegalArgumentException(fieldName + " must be a valid decimal", numberFormatException);
    }
  }
}