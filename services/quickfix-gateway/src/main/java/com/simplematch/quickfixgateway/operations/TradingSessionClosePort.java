package com.simplematch.quickfixgateway.operations;

/** Port used by Gateway operational coordination to request Risk-owned session closure. */
@FunctionalInterface
public interface TradingSessionClosePort {
  /**
   * Requests durable Close Barrier publication for the supplied trading session.
   *
   * @param tradingSessionId deterministic trading-session identity to close
   * @throws RetryableTradingSessionCloseException when a temporary failure permits bounded retry
   * @throws RuntimeException when the close fails permanently
   */
  void close(String tradingSessionId);
}
