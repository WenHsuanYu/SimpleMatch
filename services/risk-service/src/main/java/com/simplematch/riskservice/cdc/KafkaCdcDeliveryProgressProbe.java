package com.simplematch.riskservice.cdc;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.DescribeTopicsOptions;
import org.apache.kafka.clients.admin.ListConsumerGroupOffsetsOptions;
import org.apache.kafka.clients.admin.ListOffsetsOptions;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.IsolationLevel;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.TopicPartition;

/** Kafka Admin adapter that compares committed observer offsets with topic heads. */
public final class KafkaCdcDeliveryProgressProbe implements CdcDeliveryProgressProbe {
  private final Admin admin;
  private final String consumerGroup;
  private final Duration timeout;

  /**
   * Creates the adapter for one durable observer group.
   *
   * @param admin Kafka Admin client used to read topic and group progress
   * @param consumerGroup durable observer consumer-group identity
   * @param timeout maximum duration for each Kafka progress request
   * @throws NullPointerException if {@code admin} or {@code timeout} is {@code null}
   * @throws IllegalArgumentException if the group is blank or the timeout is non-positive
   */
  public KafkaCdcDeliveryProgressProbe(Admin admin, String consumerGroup, Duration timeout) {
    this.admin = Objects.requireNonNull(admin, "admin");
    if (consumerGroup == null || consumerGroup.isBlank()) {
      throw new IllegalArgumentException("consumerGroup must not be blank");
    }
    this.consumerGroup = consumerGroup;
    this.timeout = Objects.requireNonNull(timeout, "timeout");
    if (timeout.isZero() || timeout.isNegative()) {
      throw new IllegalArgumentException("timeout must be positive");
    }
  }

  /**
   * Reports whether the observer group has reached every current topic head.
   *
   * @param topic Kafka topic whose partition heads are compared with committed offsets
   * @return whether every non-empty partition has a committed observer offset at its head
   * @throws IllegalStateException if Kafka progress cannot be read before the configured timeout
   */
  @Override
  public boolean isCaughtUp(String topic) {
    final TopicDescription description =
        await(
                admin.describeTopics(
                        List.of(topic), new DescribeTopicsOptions().timeoutMs(timeoutMillis()))
                    .allTopicNames())
            .get(topic);
    if (description == null || description.partitions().isEmpty()) {
      return false;
    }
    final List<TopicPartition> partitions =
        description.partitions().stream()
            .map(info -> new TopicPartition(topic, info.partition()))
            .toList();
    final Map<TopicPartition, OffsetSpec> requests = new LinkedHashMap<>();
    partitions.forEach(partition -> requests.put(partition, OffsetSpec.latest()));
    final var endOffsets =
        await(
            admin.listOffsets(
                    requests,
                    new ListOffsetsOptions(IsolationLevel.READ_COMMITTED)
                        .timeoutMs(timeoutMillis()))
                .all());
    final Map<TopicPartition, OffsetAndMetadata> committed =
        await(
            admin.listConsumerGroupOffsets(
                    consumerGroup,
                    new ListConsumerGroupOffsetsOptions().timeoutMs(timeoutMillis()))
                .partitionsToOffsetAndMetadata());
    return partitions.stream()
        .allMatch(
            partition ->
                endOffsets.get(partition) != null
                    && ((endOffsets.get(partition).offset() == 0
                            && committed.get(partition) == null)
                        || (committed.get(partition) != null
                            && committed.get(partition).offset()
                                >= endOffsets.get(partition).offset())));
  }

  private int timeoutMillis() {
    return Math.toIntExact(timeout.toMillis());
  }

  private <T> T await(KafkaFuture<T> future) {
    try {
      return future.get(timeoutMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Kafka progress query interrupted", failure);
    } catch (ExecutionException | TimeoutException failure) {
      throw new IllegalStateException("Kafka progress query failed", failure);
    }
  }
}
