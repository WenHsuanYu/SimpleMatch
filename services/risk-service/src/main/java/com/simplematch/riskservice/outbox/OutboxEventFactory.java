package com.simplematch.riskservice.outbox;

/**
 * Produces an outbox record from a domain-specific source input.
 *
 * @param <T> the source type used to build an outbox event
 */
@FunctionalInterface
public interface OutboxEventFactory<T> {
  /**
   * Creates a new outbox record for the provided source input.
   *
   * @param source the source input
   * @return the outbox record
   */
  OutboxRecord create(T source);
}
