package com.simplematch.marketdatastreamer.stream;

import com.google.protobuf.InvalidProtocolBufferException;
import com.simplematch.contracts.marketdata.runtime.v1.MarketDataSnapshot;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;

/** Reads independently usable complete snapshots and fans them out to public subscribers. */
public final class MarketDataSnapshotKafkaConsumer {
  private static final Logger LOGGER =
      LoggerFactory.getLogger(MarketDataSnapshotKafkaConsumer.class);
  private final MarketDataSnapshotPublisher broadcaster;

  /** Creates the consumer over the public snapshot broadcaster. */
  public MarketDataSnapshotKafkaConsumer(MarketDataSnapshotPublisher broadcaster) {
    this.broadcaster = broadcaster;
  }

  /**
   * Accepts one complete snapshot. Invalid or non-snapshot values are isolated to this consumer;
   * they cannot interrupt Matching or the critical downstream consumers.
   */
  @KafkaListener(
      topics = "${simplematch.marketdata-streamer.topic:marketdata.events}",
      groupId = "${simplematch.marketdata-streamer.consumer-group:marketdata-streamer}",
      autoStartup = "${simplematch.marketdata-streamer.enabled:false}")
  public void accept(ConsumerRecord<String, byte[]> record, Acknowledgment acknowledgment) {
    final byte[] payload = record.value() == null ? new byte[0] : record.value();
    try {
      broadcaster.publish(MarketDataSnapshot.parseFrom(payload));
      acknowledgment.acknowledge();
    } catch (InvalidProtocolBufferException | IllegalArgumentException invalid) {
      LOGGER.warn("discarding invalid market-data snapshot from marketdata.events", invalid);
      acknowledgment.acknowledge();
    }
  }
}
