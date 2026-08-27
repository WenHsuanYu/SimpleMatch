package com.simplematch.riskservice.grpc;

import com.simplematch.contracts.risk.v2.CloseTradingSessionRequest;
import com.simplematch.contracts.risk.v2.CloseTradingSessionResponse;
import com.simplematch.contracts.risk.v2.TradingSessionOperationsServiceGrpc;
import com.simplematch.riskservice.admission.TradingSessionBarrierService;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;

/** Exposes Risk-owned trading-session barrier publication to operational coordination. */
@Service
@Log4j2
public final class TradingSessionOperationsGrpcService
    extends TradingSessionOperationsServiceGrpc.TradingSessionOperationsServiceImplBase {
  private final TradingSessionBarrierService barriers;

  /** Creates the transport adapter over Risk's durable barrier application service. */
  public TradingSessionOperationsGrpcService(TradingSessionBarrierService barriers) {
    this.barriers = barriers;
  }

  /** Durably accepts the deterministic Close Barrier set for one trading session. */
  @Override
  public void closeTradingSession(
      CloseTradingSessionRequest request,
      StreamObserver<CloseTradingSessionResponse> responseObserver) {
    try {
      final int inserted = barriers.close(request.getTradingSessionId());
      responseObserver.onNext(
          CloseTradingSessionResponse.newBuilder().setNewlyInsertedBarriers(inserted).build());
      responseObserver.onCompleted();
    } catch (IllegalArgumentException invalid) {
      responseObserver.onError(
          Status.INVALID_ARGUMENT.withDescription(invalid.getMessage()).asRuntimeException());
    } catch (RuntimeException failure) {
      log.error(
          "Failed to persist trading-session Close Barriers: tradingSessionId={}",
          request.getTradingSessionId(),
          failure);
      responseObserver.onError(
          Status.INTERNAL
              .withDescription("failed to persist trading-session Close Barriers")
              .asRuntimeException());
    }
  }
}
