package com.simplematch.riskservice.cdc;

/** Durable port for exact Kafka observations and admission-lag refreshes. */
public interface CdcDeliveryProgressStore {
  /** Records a Kafka observation when it belongs to this service's outbox. */
  void observe(CdcDeliveryObservation observation);

  /** Refreshes the admission metric from durable outbox and observation state. */
  CdcDeliverySnapshot refresh(String metricName, String topic, long measuredAtUnixMs);
}
