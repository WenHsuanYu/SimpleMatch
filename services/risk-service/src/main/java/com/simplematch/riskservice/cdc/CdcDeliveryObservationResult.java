package com.simplematch.riskservice.cdc;

/** Outcome of correlating one consumed Kafka record with the Risk outbox. */
public enum CdcDeliveryObservationResult {
  /** A new exact observation was persisted. */
  RECORDED,
  /** The exact event was already persisted by an earlier delivery attempt. */
  ALREADY_RECORDED,
  /** The event identity was already persisted, but the incoming envelope or position conflicts. */
  CONFLICT,
  /** No Risk outbox row matched the event's complete metadata. */
  NOT_CORRELATED
}
