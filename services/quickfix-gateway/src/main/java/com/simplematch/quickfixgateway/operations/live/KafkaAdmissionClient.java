package com.simplematch.quickfixgateway.operations.live;

/** Reads required topic topology, end offsets, and critical consumer commits from Kafka. */
@FunctionalInterface
public interface KafkaAdmissionClient {
  /** Returns a complete snapshot or throws when any required control-plane fact is unavailable. */
  KafkaAdmissionSnapshot observe();
}
