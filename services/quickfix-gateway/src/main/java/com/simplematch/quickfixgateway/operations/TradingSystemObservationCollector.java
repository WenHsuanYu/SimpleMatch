package com.simplematch.quickfixgateway.operations;

/** Production-facing port that collects one complete, internally consistent live observation. */
@FunctionalInterface
public interface TradingSystemObservationCollector {
  /** Returns one complete observation or throws without publishing a partial sample. */
  TradingSystemObservation collect();
}
