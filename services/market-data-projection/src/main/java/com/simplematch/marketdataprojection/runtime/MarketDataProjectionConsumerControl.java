package com.simplematch.marketdataprojection.runtime;

/** Controls the rebuildable projection consumer during an operator reset. */
@FunctionalInterface
public interface MarketDataProjectionConsumerControl {
  /** Stops the projection listener before durable state and offsets are reset. */
  void stop();
}
