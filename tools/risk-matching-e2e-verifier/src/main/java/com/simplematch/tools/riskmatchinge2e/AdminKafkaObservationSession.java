package com.simplematch.tools.riskmatchinge2e;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.DescribeTopicsOptions;
import org.apache.kafka.clients.admin.ListConsumerGroupOffsetsOptions;
import org.apache.kafka.clients.admin.ListConsumerGroupOffsetsSpec;
import org.apache.kafka.clients.admin.ListOffsetsOptions;
import org.apache.kafka.clients.admin.ListOffsetsResult.ListOffsetsResultInfo;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.IsolationLevel;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.TopicPartition;

/** Kafka Admin adapter for {@link KafkaObservationSession}. */
final class AdminKafkaObservationSession implements KafkaObservationSession {
  private static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(2);

  private final Admin admin;
  private final String commandsTopic;
  private final String eventsTopic;
  private final int queryTimeoutMillis;
  private final List<TopicPartition> commandPartitions;
  private final List<TopicPartition> eventPartitions;

  AdminKafkaObservationSession(
      String bootstrapServers, String commandsTopic, String eventsTopic, int queryTimeoutMillis) {
    this.commandsTopic = requireText(commandsTopic, "commands topic");
    this.eventsTopic = requireText(eventsTopic, "events topic");
    if (queryTimeoutMillis <= 0) {
      throw new IllegalArgumentException("query timeout must be positive");
    }
    this.queryTimeoutMillis = queryTimeoutMillis;

    final Properties properties = new Properties();
    properties.put(
        AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
        requireText(bootstrapServers, "bootstrap servers"));
    properties.put(AdminClientConfig.CLIENT_ID_CONFIG, "critical-consumer-kafka-observer");
    properties.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, queryTimeoutMillis);
    properties.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, queryTimeoutMillis);
    admin = Admin.create(properties);

    try {
      final Map<String, TopicDescription> descriptions =
          await(
              admin.describeTopics(
                      List.of(this.commandsTopic, this.eventsTopic),
                      new DescribeTopicsOptions().timeoutMs(queryTimeoutMillis))
                  .allTopicNames());
      commandPartitions = requireExpectedPartitions(descriptions.get(this.commandsTopic));
      eventPartitions = requireExpectedPartitions(descriptions.get(this.eventsTopic));
    } catch (RuntimeException failure) {
      admin.close(CLOSE_TIMEOUT);
      throw failure;
    }
  }

  @Override
  public LogEndPositions captureLogEndPositions() {
    final Map<TopicPartition, OffsetSpec> requests = new LinkedHashMap<>();
    commandPartitions.forEach(partition -> requests.put(partition, OffsetSpec.latest()));
    eventPartitions.forEach(partition -> requests.put(partition, OffsetSpec.latest()));

    final Map<TopicPartition, ListOffsetsResultInfo> result =
        await(
            admin.listOffsets(
                    requests,
                    new ListOffsetsOptions(IsolationLevel.READ_COMMITTED)
                        .timeoutMs(queryTimeoutMillis))
                .all());

    return new LogEndPositions(
        toTopicEndPositions(commandsTopic, commandPartitions, result),
        toTopicEndPositions(eventsTopic, eventPartitions, result));
  }

  @Override
  public MatchingCommittedPositions captureMatchingCommittedPositions() {
    final Map<String, ListConsumerGroupOffsetsSpec> groupSpecs = new LinkedHashMap<>();
    for (TopicPartition partition : commandPartitions) {
      final String groupId = "matching-partition-consumer-" + partition.partition();
      groupSpecs.put(
          groupId, new ListConsumerGroupOffsetsSpec().topicPartitions(List.of(partition)));
    }

    final Map<String, Map<TopicPartition, OffsetAndMetadata>> result =
        await(
            admin.listConsumerGroupOffsets(
                    groupSpecs,
                    new ListConsumerGroupOffsetsOptions().timeoutMs(queryTimeoutMillis))
                .all());

    final List<PartitionCommittedOffset> partitions = new ArrayList<>();
    for (TopicPartition partition : commandPartitions) {
      final String groupId = "matching-partition-consumer-" + partition.partition();
      final Map<TopicPartition, OffsetAndMetadata> groupOffsets = result.get(groupId);
      if (groupOffsets == null || groupOffsets.size() != 1) {
        throw new IllegalStateException("missing committed position for " + groupId);
      }
      final OffsetAndMetadata offset = groupOffsets.get(partition);
      if (offset == null) {
        throw new IllegalStateException(
            groupId + " has no committed offset for partition " + partition.partition());
      }
      partitions.add(new PartitionCommittedOffset(partition.partition(), offset.offset()));
    }
    return new MatchingCommittedPositions(commandsTopic, partitions);
  }

  @Override
  public void close() {
    admin.close(CLOSE_TIMEOUT);
  }

  private static TopicEndPositions toTopicEndPositions(
      String topic,
      List<TopicPartition> partitions,
      Map<TopicPartition, ListOffsetsResultInfo> result) {
    final List<PartitionEndOffset> positions = new ArrayList<>();
    for (TopicPartition partition : partitions) {
      final ListOffsetsResultInfo info = result.get(partition);
      if (info == null) {
        throw new IllegalStateException(
            "missing log-end position for " + topic + " partition " + partition.partition());
      }
      positions.add(new PartitionEndOffset(partition.partition(), info.offset()));
    }
    return new TopicEndPositions(topic, positions);
  }

  private static List<TopicPartition> requireExpectedPartitions(TopicDescription description) {
    if (description == null) {
      throw new IllegalStateException("Kafka topic metadata is missing");
    }
    final List<TopicPartition> partitions =
        description.partitions().stream()
            .map(info -> new TopicPartition(description.name(), info.partition()))
            .sorted(Comparator.comparingInt(TopicPartition::partition))
            .toList();
    if (partitions.size() != EXPECTED_PARTITION_COUNT) {
      throw new IllegalStateException(
          description.name()
              + " exposes "
              + partitions.size()
              + " partitions; expected "
              + EXPECTED_PARTITION_COUNT);
    }
    for (int partition = 0; partition < EXPECTED_PARTITION_COUNT; partition++) {
      if (partitions.get(partition).partition() != partition) {
        throw new IllegalStateException(
            description.name()
                + " must expose partitions 0 through "
                + (EXPECTED_PARTITION_COUNT - 1));
      }
    }
    return List.copyOf(partitions);
  }

  private <T> T await(KafkaFuture<T> future) {
    try {
      return future.get(queryTimeoutMillis, TimeUnit.MILLISECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Kafka observation was interrupted", interrupted);
    } catch (ExecutionException | TimeoutException failure) {
      throw new IllegalStateException("Kafka observation did not complete", failure);
    }
  }

  private static String requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " is required");
    }
    return value;
  }
}
