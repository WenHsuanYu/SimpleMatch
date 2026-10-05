package com.simplematch.quickfixgateway.operations.live;

import com.simplematch.quickfixgateway.config.GatewayLiveObservationProperties;
import com.simplematch.quickfixgateway.operations.CriticalConsumer;
import java.time.Clock;
import java.time.Duration;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.ListOffsetsResult.ListOffsetsResultInfo;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.TopicPartition;

/** Kafka Admin adapter for required topology, end offsets, and durable group progress. */
public final class AdminKafkaAdmissionClient implements KafkaAdmissionClient {
  private static final int EXPECTED_PARTITION_COUNT = 15;
  private final Admin admin;
  private final String commandsTopic;
  private final String eventsTopic;
  private final Map<CriticalConsumer, String> consumerGroups;
  private final Duration timeout;
  private final Clock clock;

  /** Creates the bounded Kafka control-plane adapter. */
  public AdminKafkaAdmissionClient(
      Admin admin,
      String commandsTopic,
      String eventsTopic,
      GatewayLiveObservationProperties.ConsumerGroups consumerGroups,
      Duration timeout,
      Clock clock) {
    this.admin = Objects.requireNonNull(admin, "admin");
    this.commandsTopic = required(commandsTopic, "commandsTopic");
    this.eventsTopic = required(eventsTopic, "eventsTopic");
    this.consumerGroups =
        Map.of(
            CriticalConsumer.PERSISTENCE,
            consumerGroups.persistence(),
            CriticalConsumer.ACCOUNT,
            consumerGroups.account(),
            CriticalConsumer.QUICKFIX,
            consumerGroups.quickfix());
    this.timeout = Objects.requireNonNull(timeout, "timeout");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Override
  public KafkaAdmissionSnapshot observe() {
    try {
      final KafkaFuture<Map<String, TopicDescription>> descriptionsPending =
          admin.describeTopics(List.of(commandsTopic, eventsTopic)).allTopicNames();
      final Map<TopicPartition, OffsetSpec> endOffsetRequest = new HashMap<>();
      for (int partition = 0; partition < EXPECTED_PARTITION_COUNT; partition++) {
        endOffsetRequest.put(new TopicPartition(commandsTopic, partition), OffsetSpec.latest());
        endOffsetRequest.put(new TopicPartition(eventsTopic, partition), OffsetSpec.latest());
      }
      final KafkaFuture<Map<TopicPartition, ListOffsetsResultInfo>> endOffsetsPending =
          admin.listOffsets(endOffsetRequest).all();
      final Map<Integer, KafkaFuture<Map<TopicPartition, OffsetAndMetadata>>> matchingPending =
          matchingCommitsPending();
      final EnumMap<CriticalConsumer, KafkaFuture<Map<TopicPartition, OffsetAndMetadata>>>
          commitsPending = new EnumMap<>(CriticalConsumer.class);
      consumerGroups.forEach(
          (consumer, group) ->
              commitsPending.put(
                  consumer,
                  admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata()));
      final Map<String, TopicDescription> descriptions =
          descriptionsPending.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
      final TopicDescription commands = requiredDescription(descriptions, commandsTopic);
      final TopicDescription events = requiredDescription(descriptions, eventsTopic);
      final Map<TopicPartition, ListOffsetsResultInfo> endOffsets =
          endOffsetsPending.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
      final Map<Integer, Long> commandEnds = endOffsets(commandsTopic, commands, endOffsets);
      final Map<Integer, Long> eventEnds = endOffsets(eventsTopic, events, endOffsets);
      final EnumMap<CriticalConsumer, Map<Integer, Long>> commits =
          new EnumMap<>(CriticalConsumer.class);
      for (Map.Entry<
              CriticalConsumer, KafkaFuture<Map<TopicPartition, OffsetAndMetadata>>>
          group : commitsPending.entrySet()) {
        commits.put(
            group.getKey(),
            committedOffsets(
                group.getValue().get(timeout.toMillis(), TimeUnit.MILLISECONDS), eventsTopic));
      }
      return new KafkaAdmissionSnapshot(
          commands.partitions().size(),
          events.partitions().size(),
          commandEnds,
          matchingCommittedOffsets(matchingPending),
          eventEnds,
          commits,
          clock.instant());
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Kafka admission observation interrupted", failure);
    } catch (ExecutionException | TimeoutException failure) {
      throw new IllegalStateException("Kafka admission observation unavailable", failure);
    }
  }

  private Map<Integer, KafkaFuture<Map<TopicPartition, OffsetAndMetadata>>>
      matchingCommitsPending() {
    final Map<Integer, KafkaFuture<Map<TopicPartition, OffsetAndMetadata>>> pending =
        new HashMap<>();
    for (int partition = 0; partition < EXPECTED_PARTITION_COUNT; partition++) {
      pending.put(
          partition,
          admin.listConsumerGroupOffsets("matching-partition-consumer-" + partition)
              .partitionsToOffsetAndMetadata());
    }
    return pending;
  }

  private Map<Integer, Long> matchingCommittedOffsets(
      Map<Integer, KafkaFuture<Map<TopicPartition, OffsetAndMetadata>>> pending)
      throws InterruptedException, ExecutionException, TimeoutException {
    final Map<Integer, Long> normalized = new HashMap<>();
    for (Map.Entry<Integer, KafkaFuture<Map<TopicPartition, OffsetAndMetadata>>> group
        : pending.entrySet()) {
      final int partition = group.getKey();
      final Map<Integer, Long> offsets = committedOffsets(
          group.getValue().get(timeout.toMillis(), TimeUnit.MILLISECONDS), commandsTopic);
      if (offsets.keySet().stream().anyMatch(observedPartition -> observedPartition != partition)) {
        throw new IllegalStateException("Matching group committed another partition: " + partition);
      }
      final Long offset = offsets.get(partition);
      if (offset != null) {
        normalized.put(partition, offset);
      }
    }
    return Map.copyOf(normalized);
  }

  private Map<Integer, Long> endOffsets(
      String topic,
      TopicDescription description,
      Map<TopicPartition, ListOffsetsResultInfo> offsets) {
    final Map<Integer, Long> normalized = new HashMap<>();
    offsets.forEach(
        (partition, result) -> {
          if (!partition.topic().equals(topic)) {
            return;
          }
          if (result.offset() < 0) {
            throw new IllegalStateException("Kafka end offset is negative: " + partition);
          }
          normalized.put(partition.partition(), result.offset());
        });
    if (normalized.size() != description.partitions().size()) {
      throw new IllegalStateException("Kafka end offsets are incomplete for " + topic);
    }
    return Map.copyOf(normalized);
  }

  private Map<Integer, Long> committedOffsets(
      Map<TopicPartition, OffsetAndMetadata> offsets, String requiredTopic) {
    final Map<Integer, Long> normalized = new HashMap<>();
    offsets.forEach(
        (partition, offset) -> {
          if (!partition.topic().equals(requiredTopic)) {
            return;
          }
          if (offset.offset() < 0) {
            throw new IllegalStateException("Kafka committed offset is negative: " + partition);
          }
          normalized.put(partition.partition(), offset.offset());
        });
    return Map.copyOf(normalized);
  }

  private static TopicDescription requiredDescription(
      Map<String, TopicDescription> descriptions, String topic) {
    final TopicDescription description = descriptions.get(topic);
    if (description == null || description.partitions().isEmpty()) {
      throw new IllegalStateException("Kafka topic description is missing: " + topic);
    }
    return description;
  }

  private static String required(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }
}
