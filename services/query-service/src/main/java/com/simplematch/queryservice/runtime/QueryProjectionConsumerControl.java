package com.simplematch.queryservice.runtime;

/** Controls both rebuildable query projection consumers during an operator reset. */
@FunctionalInterface
public interface QueryProjectionConsumerControl {
  /** Stops Matching and Account listeners before durable state and offsets are reset. */
  void stop();
}
