package com.simplematch.riskservice.cdc;

/** Kafka progress port used to prove that the observer group reached the topic head. */
@FunctionalInterface
public interface CdcDeliveryProgressProbe {
  /** Returns whether every topic partition has a committed offset at its current head. */
  boolean isCaughtUp(String topic);
}
