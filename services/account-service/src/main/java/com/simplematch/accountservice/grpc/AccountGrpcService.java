package com.simplematch.accountservice.grpc;

import com.simplematch.contracts.account.v1.AccountServiceGrpc;
import com.simplematch.contracts.account.v1.ApplyFillRequest;
import com.simplematch.contracts.account.v1.GetLimitsRequest;
import com.simplematch.contracts.account.v1.GetPositionsRequest;
import com.simplematch.contracts.account.v1.ReleaseReservationRequest;
import com.simplematch.contracts.account.v1.ReserveRequest;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.springframework.stereotype.Service;

/**
 * gRPC skeleton for account-service control-plane operations.
 *
 * <p>The write RPCs in this service intentionally use {@code request_id} as the synchronous name
 * for the same operation identifier that enters the trading flow as {@code command_id} on
 * {@code OrderCommand}. This service is still unimplemented, but the contract note is kept here so
 * future implementations preserve that mapping explicitly rather than treating the two names as
 * unrelated identities.
 */
@Service
public class AccountGrpcService extends AccountServiceGrpc.AccountServiceImplBase {
  private static final String MESSAGE = "account-service logic is not implemented yet";

  @Override
  public void getLimits(GetLimitsRequest request, StreamObserver<com.simplematch.contracts.account.v1.GetLimitsResponse> responseObserver) {
    responseObserver.onError(Status.UNIMPLEMENTED.withDescription(MESSAGE).asRuntimeException());
  }

  @Override
  public void getPositions(GetPositionsRequest request, StreamObserver<com.simplematch.contracts.account.v1.GetPositionsResponse> responseObserver) {
    responseObserver.onError(Status.UNIMPLEMENTED.withDescription(MESSAGE).asRuntimeException());
  }

  /**
   * Rejects the unimplemented reservation RPC.
   *
   * <p>{@code request_id} is the control-plane name for the upstream operation identifier currently
   * carried as {@code command_id} on order events.
   */
  @Override
  public void reserve(ReserveRequest request, StreamObserver<com.simplematch.contracts.account.v1.ReserveResponse> responseObserver) {
    responseObserver.onError(Status.UNIMPLEMENTED.withDescription(MESSAGE).asRuntimeException());
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
}