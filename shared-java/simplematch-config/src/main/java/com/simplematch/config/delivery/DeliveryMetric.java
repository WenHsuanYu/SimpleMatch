package com.simplematch.config.delivery;

/** Stable metric names shared by CDC, consumer, and outbox-retention adapters. */
public enum DeliveryMetric {
  /** Number of events between the connector source and its published position. */
  CONNECTOR_LAG_EVENTS,
  /** Age in milliseconds of the oldest retained outbox row that is being observed. */
  OUTBOX_AGE_MILLIS,
  /** Number of events between a consumer position and its partition head. */
  CONSUMER_LAG_EVENTS,
  /** Number of duplicate deliveries recognized by a consumer. */
  DUPLICATE,
  /** Number of in-place or delayed retry attempts. */
  RETRY,
  /** Number of critical records moved into quarantine. */
  QUARANTINE,
  /** Number of non-critical records sent to a dead-letter destination. */
  DEAD_LETTER
}
