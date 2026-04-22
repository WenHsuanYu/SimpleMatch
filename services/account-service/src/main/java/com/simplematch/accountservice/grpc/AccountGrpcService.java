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

  @Override
  public void reserve(ReserveRequest request, StreamObserver<com.simplematch.contracts.account.v1.ReserveResponse> responseObserver) {
    responseObserver.onError(Status.UNIMPLEMENTED.withDescription(MESSAGE).asRuntimeException());
  }

  @Override
  public void releaseReservation(ReleaseReservationRequest request, StreamObserver<com.simplematch.contracts.account.v1.ReleaseReservationResponse> responseObserver) {
    responseObserver.onError(Status.UNIMPLEMENTED.withDescription(MESSAGE).asRuntimeException());
  }

  @Override
  public void applyFill(ApplyFillRequest request, StreamObserver<com.simplematch.contracts.account.v1.ApplyFillResponse> responseObserver) {
    responseObserver.onError(Status.UNIMPLEMENTED.withDescription(MESSAGE).asRuntimeException());
  }
}