package com.simplematch.riskservice.grpc;

import com.simplematch.contracts.risk.v2.CloseTradingSessionRequest;
import com.simplematch.contracts.risk.v2.CloseTradingSessionResponse;
import com.simplematch.contracts.risk.v2.TradingSessionOperationsServiceGrpc;
import com.simplematch.riskservice.admission.TradingSessionBarrierService;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import lombok.extern.log4j.Log4j2;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionTimedOutException;

/** Exposes Risk-owned trading-session barrier publication to operational coordination. */
@Service
@Log4j2
public final class TradingSessionOperationsGrpcService
    extends TradingSessionOperationsServiceGrpc.TradingSessionOperationsServiceImplBase {
  private final TradingSessionBarrierService barriers;

  /**
   * Creates the transport adapter over Risk's durable barrier application service.
   *
   * @param barriers application service that owns deterministic barrier persistence
   */
  public TradingSessionOperationsGrpcService(TradingSessionBarrierService barriers) {
    this.barriers = barriers;
  }

  /**
   * Durably accepts the deterministic Close Barrier set for one trading session.
   *
   * @param request trading-session close request
   * @param responseObserver generated gRPC response observer
   */
  @Override
  public void closeTradingSession(
      CloseTradingSessionRequest request,
      StreamObserver<CloseTradingSessionResponse> responseObserver) {
    try {
      barriers.close(request.getTradingSessionId());
      responseObserver.onNext(CloseTradingSessionResponse.getDefaultInstance());
      responseObserver.onCompleted();
    } catch (IllegalArgumentException invalid) {
      responseObserver.onError(
          Status.INVALID_ARGUMENT.withDescription(invalid.getMessage()).asRuntimeException());
    } catch (RuntimeException failure) {
      respondWithPersistenceFailure(request, responseObserver, failure);
    }
  }

  private void respondWithPersistenceFailure(
      CloseTradingSessionRequest request,
      StreamObserver<CloseTradingSessionResponse> responseObserver,
      RuntimeException failure) {
    if (isTemporarilyUnavailable(failure)) {
      log.error(
          "Trading-session Close Barrier persistence is temporarily unavailable: "
              + "tradingSessionId={}",
          request.getTradingSessionId(),
          failure);
      responseObserver.onError(
          Status.UNAVAILABLE
              .withDescription(
                  "trading-session Close Barrier persistence is temporarily unavailable")
              .asRuntimeException());
      return;
    }

    log.error(
        "Unexpected trading-session close failure: tradingSessionId={}",
        request.getTradingSessionId(),
        failure);
    responseObserver.onError(
        Status.INTERNAL
            .withDescription("unexpected trading-session close failure")
            .asRuntimeException());
  }

  private boolean isTemporarilyUnavailable(RuntimeException failure) {
    return failure instanceof TransientDataAccessException
        || failure instanceof RecoverableDataAccessException
        || failure instanceof DataAccessResourceFailureException
        || failure instanceof CannotCreateTransactionException
        || failure instanceof TransactionTimedOutException;
  }
}
