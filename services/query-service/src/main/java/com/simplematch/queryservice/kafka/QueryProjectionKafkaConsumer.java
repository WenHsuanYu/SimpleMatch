package com.simplematch.queryservice.kafka;

import com.google.protobuf.InvalidProtocolBufferException;
import com.simplematch.contracts.account.v2.AccountLifecycleEvent;
import com.simplematch.contracts.matching.runtime.v1.FinalMatchingEventEnvelope;
import com.simplematch.queryservice.runtime.QueryProjectionApplicationService;
import com.simplematch.queryservice.store.QueryProjectionGapException;
import com.simplematch.queryservice.store.QueryProjectionStore;
import java.time.Clock;
import java.util.Arrays;
import java.util.Objects;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;

/** Runs independent asynchronous consumers for final Matching and Account lifecycle facts. */
public final class QueryProjectionKafkaConsumer {
  private static final Logger LOGGER = LoggerFactory.getLogger(QueryProjectionKafkaConsumer.class);
  private final QueryProjectionApplicationService projectionService;
  private final QueryProjectionStore store;
  private final Clock clock;

  /** Creates the query-only consumer seam; no critical service is called from this class. */
  public QueryProjectionKafkaConsumer(
      QueryProjectionApplicationService projectionService,
      QueryProjectionStore store,
      Clock clock) {
    this.projectionService = Objects.requireNonNull(projectionService, "projectionService");
    this.store = Objects.requireNonNull(store, "store");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /** Applies a final Matching Event only after the local PostgreSQL transaction succeeds. */
  @KafkaListener(
      topics = "${simplematch.query-service.matching-events.topic:matching.events}",
      groupId =
          "${simplematch.query-service.matching-events.consumer-group:"
              + "query-service-matching-events}",
      autoStartup = "${simplematch.query-service.matching-events.enabled:false}",
      properties = "key.deserializer=org.apache.kafka.common.serialization.ByteArrayDeserializer")
  public void onMatchingEvent(
      ConsumerRecord<byte[], byte[]> record, Acknowledgment acknowledgment) {
    final byte[] rawPayload = record.value() == null ? new byte[0] : record.value();
    try {
      final FinalMatchingEventEnvelope envelope = FinalMatchingEventEnvelope.parse(rawPayload);
      requireExactKafkaKey(record.key(), envelope.eventIdBytes());
      requireExactPartition(record.partition(), envelope.event().getPartitionId());
      projectionService.projectMatching(
          envelope, record.partition(), record.offset(), clock.millis());
      acknowledgment.acknowledge();
    } catch (QueryProjectionGapException gap) {
      store.markRecoveryRequired(
          "matching.events", record.partition(), record.offset(), clock.millis());
      LOGGER.warn(
          "query Matching projection paused for replay at {}-{}",
          record.partition(),
          record.offset(),
          gap);
      acknowledgment.acknowledge();
    } catch (RuntimeException | InvalidProtocolBufferException failure) {
      LOGGER.warn(
          "query Matching projection failed at {}-{}; source offset remains unacknowledged",
          record.partition(),
          record.offset(),
          failure);
      throw new IllegalStateException("query Matching projection failed", failure);
    }
  }

  /** Applies an Account lifecycle fact only after the local PostgreSQL transaction succeeds. */
  @KafkaListener(
      topics = "${simplematch.query-service.account-lifecycle.topic:account.lifecycle}",
      groupId =
          "${simplematch.query-service.account-lifecycle.consumer-group:"
              + "query-service-account-lifecycle}",
      autoStartup = "${simplematch.query-service.account-lifecycle.enabled:false}")
  public void onAccountLifecycle(
      ConsumerRecord<String, byte[]> record, Acknowledgment acknowledgment) {
    final byte[] rawPayload = record.value() == null ? new byte[0] : record.value();
    try {
      projectionService.projectAccountLifecycle(
          AccountLifecycleEvent.parseFrom(rawPayload),
          rawPayload,
          record.partition(),
          record.offset(),
          clock.millis());
      acknowledgment.acknowledge();
    } catch (QueryProjectionGapException gap) {
      store.markRecoveryRequired(
          "account.lifecycle", record.partition(), record.offset(), clock.millis());
      LOGGER.warn(
          "query Account projection paused for replay at {}-{}",
          record.partition(),
          record.offset(),
          gap);
      acknowledgment.acknowledge();
    } catch (RuntimeException | InvalidProtocolBufferException failure) {
      LOGGER.warn(
          "query Account projection failed at {}-{}; source offset remains unacknowledged",
          record.partition(),
          record.offset(),
          failure);
      throw new IllegalStateException("query Account projection failed", failure);
    }
  }

  private void requireExactKafkaKey(byte[] recordKey, byte[] eventId) {
    if (recordKey == null || !Arrays.equals(eventId, recordKey)) {
      throw new IllegalArgumentException("matching.events Kafka key must equal eventId bytes");
    }
  }

  private void requireExactPartition(int recordPartition, int eventPartition) {
    if (recordPartition != eventPartition) {
      throw new IllegalArgumentException("matching.events Kafka partition must equal partitionId");
    }
  }
}
