package com.simplematch.quickfixgateway.risk;

import com.simplematch.contracts.risk.v2.CloseTradingSessionRequest;
import com.simplematch.contracts.risk.v2.TradingSessionOperationsServiceGrpc;
import com.simplematch.quickfixgateway.operations.RetryableTradingSessionCloseException;
import com.simplematch.quickfixgateway.operations.TradingSessionClosePort;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.util.concurrent.TimeUnit;

/** Requests Risk-owned durable trading-session closure through the existing Risk channel. */
public final class GrpcTradingSessionCloseClient implements TradingSessionClosePort {
  private final TradingSessionOperationsServiceGrpc.TradingSessionOperationsServiceBlockingStub
      blockingStub;
  private final long deadlineMillis;

  /**
   * Creates the close adapter for an existing managed Risk channel.
   *
   * @param managedChannel shared channel to Risk
   * @param deadlineMillis bounded deadline applied to each close request
   */
  public GrpcTradingSessionCloseClient(ManagedChannel managedChannel, long deadlineMillis) {
    blockingStub = TradingSessionOperationsServiceGrpc.newBlockingStub(managedChannel);
    this.deadlineMillis = deadlineMillis;
  }

  /** {@inheritDoc} */
  @Override
  public void close(String tradingSessionId) {
    try {
      blockingStub
          .withDeadlineAfter(deadlineMillis, TimeUnit.MILLISECONDS)
          .closeTradingSession(
              CloseTradingSessionRequest.newBuilder()
                  .setTradingSessionId(tradingSessionId)
                  .build());
    } catch (StatusRuntimeException failure) {
      if (isRetryable(failure.getStatus().getCode())) {
        throw new RetryableTradingSessionCloseException(
            "Risk trading-session close transport is temporarily unavailable", failure);
      }
      throw failure;
    }
  }

  private boolean isRetryable(Status.Code code) {
    return switch (code) {
      case ABORTED, DEADLINE_EXCEEDED, RESOURCE_EXHAUSTED, UNAVAILABLE -> true;
      default -> false;
    };
  }
}
