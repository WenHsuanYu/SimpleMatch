package com.simplematch.quickfixgateway.operations;

/** Port used by Gateway operational coordination to request Risk-owned session closure. */
@FunctionalInterface
public interface TradingSessionClosePort {
  /** Requests durable Close Barrier publication for the supplied trading session. */
  void close(String tradingSessionId);
}
