package com.simplematch.quickfixgateway.risk;

import com.simplematch.contracts.risk.v2.CloseTradingSessionRequest;
import com.simplematch.contracts.risk.v2.TradingSessionOperationsServiceGrpc;
import com.simplematch.quickfixgateway.operations.TradingSessionClosePort;
import io.grpc.ManagedChannel;
import java.util.concurrent.TimeUnit;

/** Requests Risk-owned durable trading-session closure through the existing Risk channel. */
public final class GrpcTradingSessionCloseClient implements TradingSessionClosePort {
  private final TradingSessionOperationsServiceGrpc.TradingSessionOperationsServiceBlockingStub
      blockingStub;
  private final long deadlineMillis;

  /** Creates the close adapter for an existing managed Risk channel. */
  public GrpcTradingSessionCloseClient(ManagedChannel managedChannel, long deadlineMillis) {
    blockingStub = TradingSessionOperationsServiceGrpc.newBlockingStub(managedChannel);
    this.deadlineMillis = deadlineMillis;
  }

  /** Requests idempotent durable Close Barrier publication for one trading session. */
  @Override
  public void close(String tradingSessionId) {
    blockingStub
        .withDeadlineAfter(deadlineMillis, TimeUnit.MILLISECONDS)
        .closeTradingSession(
            CloseTradingSessionRequest.newBuilder().setTradingSessionId(tradingSessionId).build());
  }
}
