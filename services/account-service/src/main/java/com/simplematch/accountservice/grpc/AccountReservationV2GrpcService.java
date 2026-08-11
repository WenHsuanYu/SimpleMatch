package com.simplematch.accountservice.grpc;

import com.simplematch.accountservice.reservation.AccountReservationApplicationService;
import com.simplematch.accountservice.reservation.ReservationRecord;
import com.simplematch.accountservice.reservation.ReservationRequestConflictException;
import com.simplematch.contracts.account.v2.AccountLifecycleEvent;
import com.simplematch.contracts.account.v2.AccountLifecycleState;
import com.simplematch.contracts.account.v2.AccountReservationServiceGrpc;
import com.simplematch.contracts.account.v2.ReservationCommand;
import com.simplematch.contracts.common.v2.EventMetadata;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.math.BigDecimal;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Adapts the typed v2 reservation RPC to the Account Authority application service. */
@Service
@RequiredArgsConstructor
public final class AccountReservationV2GrpcService
    extends AccountReservationServiceGrpc.AccountReservationServiceImplBase {
  private static final int TWD_SCALE = 4;
  private static final String SOURCE_SERVICE = "account-service";

  @NonNull private final AccountReservationApplicationService reservationService;

  /**
   * Reserves authority and returns the resulting lifecycle state without opening a caller scope.
   */
  @Override
  public void reserve(
      ReservationCommand request, StreamObserver<AccountLifecycleEvent> responseObserver) {
    try {
      final ReservationRecord reservation =
          reservationService.reserve(
              AccountReservationV2CommandAdapter.toReserveOperation(request));
      responseObserver.onNext(toOutcome(request, reservation));
      responseObserver.onCompleted();
    } catch (ReservationRequestConflictException conflict) {
      responseObserver.onError(
          Status.ALREADY_EXISTS.withDescription("reservation request conflicts with existing facts")
              .asRuntimeException());
    } catch (IllegalArgumentException invalid) {
      responseObserver.onError(
          Status.INVALID_ARGUMENT.withDescription(invalid.getMessage()).asRuntimeException());
    } catch (RuntimeException failure) {
      responseObserver.onError(
          Status.INTERNAL.withDescription("failed to persist reservation").asRuntimeException());
    }
  }

  private AccountLifecycleEvent toOutcome(
      ReservationCommand request, ReservationRecord reservation) {
    return AccountLifecycleEvent.newBuilder()
        .setMetadata(responseMetadata(request.getMetadata(), request.getCommandId()))
        .setReservationId(reservation.reservationId())
        .setOrderId(reservation.orderId())
        .setAccountId(reservation.accountId())
        .setState(toLifecycleState(reservation))
        .setReservedNotional(
            com.simplematch.contracts.common.v2.TwdNotional.newBuilder()
                .setUnits(toFixedUnits(reservation.reservedNotional()))
                .build())
        .setReservedQuantity(
            com.simplematch.contracts.orders.v2.ShareQuantity.newBuilder()
                .setShares(reservation.quantity().longValueExact())
                .build())
        .setReasonCode(reservation.reasonCode())
        .setReasonDetail(reservation.reasonText())
        .build();
  }

  private EventMetadata responseMetadata(EventMetadata requestMetadata, String commandId) {
    final EventMetadata.Builder metadata =
        EventMetadata.newBuilder()
            .setSchemaVersion("v2")
            .setEventId(
                requestMetadata.getEventId().isBlank() ? commandId : requestMetadata.getEventId())
            .setCreatedAtUnixMs(requestMetadata.getCreatedAtUnixMs())
            .setSourceService(SOURCE_SERVICE)
            .setCausationId(commandId);
    if (!requestMetadata.getCorrelationId().isBlank()) {
      metadata.setCorrelationId(requestMetadata.getCorrelationId());
    }
    return metadata.build();
  }

  private AccountLifecycleState toLifecycleState(ReservationRecord reservation) {
    return switch (reservation.status()) {
      case RESERVATION_STATUS_ACCEPTED -> AccountLifecycleState.ACCOUNT_LIFECYCLE_STATE_RESERVED;
      case RESERVATION_STATUS_REJECTED -> AccountLifecycleState.ACCOUNT_LIFECYCLE_STATE_REJECTED;
      case RESERVATION_STATUS_RELEASED -> AccountLifecycleState.ACCOUNT_LIFECYCLE_STATE_RELEASED;
      case RESERVATION_STATUS_APPLIED -> AccountLifecycleState.ACCOUNT_LIFECYCLE_STATE_FILLED;
      default -> throw new IllegalStateException("unsupported reservation status");
    };
  }

  private long toFixedUnits(BigDecimal value) {
    try {
      return value.movePointRight(TWD_SCALE).longValueExact();
    } catch (ArithmeticException invalid) {
      throw new IllegalStateException(
          "reservation notional is outside the v2 fixed-point range", invalid);
    }
  }
}
