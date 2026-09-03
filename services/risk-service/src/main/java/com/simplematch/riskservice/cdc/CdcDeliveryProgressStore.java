package com.simplematch.riskservice.cdc;

/** Durable port for exact Kafka observations and admission-lag refreshes. */
public interface CdcDeliveryProgressStore {
  /**
   * Records a Kafka observation when it belongs to this service's outbox.
   *
   * @param observation exact event and Kafka position observed by the consumer
   * @return whether the observation was newly recorded, already recorded, conflicting, or not
   *     correlated
   */
  CdcDeliveryObservationResult observe(CdcDeliveryObservation observation);

  /**
   * Refreshes the admission metric from durable outbox and observation state.
   *
   * @param metricName durable metric row to update
   * @param topic outbox topic whose undelivered events are measured
   * @param measuredAtUnixMs timestamp assigned to the refresh
   * @return measured backlog and oldest-undelivered age
   */
  CdcDeliverySnapshot refresh(String metricName, String topic, long measuredAtUnixMs);
}
