package com.simplematch.tools.dependencies;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.simplematch.tools.recovery.CdcPublicationVerifier;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.CloseOptions;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;

/** Observes actual records after a fresh end-offset baseline without committing consumer state. */
final class KafkaPublication implements AutoCloseable {
  private final KafkaConsumer<byte[], byte[]> consumer;
  private final Admin metadata;
  private final List<JsonNode> publications = new ArrayList<>();
  private final ObjectMapper mapper = new ObjectMapper();

  KafkaPublication(String bootstrap) {
    final Properties properties = new Properties();
    properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
    properties.put(
        ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
    properties.put(
        ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
    properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
    properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "none");
    properties.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
    properties.put(ConsumerConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, "10000");
    consumer = new KafkaConsumer<>(properties);
    metadata =
        Admin.create(
            Map.of(
                "bootstrap.servers",
                bootstrap,
                "default.api.timeout.ms",
                "10000",
                "request.timeout.ms",
                "10000"));
  }

  ObjectNode baseline(CdcOwner owner) {
    final var result = mapper.createObjectNode();
    consumer
        .endOffsets(partitions(owner), Duration.ofSeconds(10))
        .forEach(
            (partition, offset) -> result.put(Integer.toString(partition.partition()), offset));
    return result;
  }

  JsonNode verify(JsonNode probe, JsonNode baseline) {
    final String topic = probe.path("topic").textValue();
    final List<TopicPartition> partitions = new ArrayList<>();
    baseline
        .properties()
        .forEach(
            entry -> partitions.add(new TopicPartition(topic, Integer.parseInt(entry.getKey()))));
    consumer.assign(partitions);
    for (TopicPartition partition : partitions) {
      consumer.seek(partition, baseline.path(Integer.toString(partition.partition())).longValue());
    }
    final Instant deadline = Instant.now().plusSeconds(90);
    while (Instant.now().isBefore(deadline)) {
      for (var record : consumer.poll(Duration.ofMillis(250))) {
        boolean matching = false;
        for (var header : record.headers().headers("id")) {
          matching |=
              java.util.Arrays.equals(
                  header.value(),
                  probe
                      .path("event_id")
                      .textValue()
                      .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        if (matching) {
          final ObjectNode fact =
              mapper
                  .createObjectNode()
                  .put("topic", record.topic())
                  .put("partition", record.partition())
                  .put("offset", record.offset())
                  .put("timestamp_unix_ms", record.timestamp())
                  .put("key_hex", HexFormat.of().formatHex(record.key()))
                  .put("payload_hex", HexFormat.of().formatHex(record.value()));
          final var current = fact.putArray("partitions");
          description(topic).partitions().forEach(partition -> current.add(partition.partition()));
          final var headers = fact.putArray("headers");
          record
              .headers()
              .forEach(
                  header ->
                      headers
                          .addObject()
                          .put("name", header.key())
                          .put("value_hex", HexFormat.of().formatHex(header.value())));
          final var result = CdcPublicationVerifier.verify(probe, baseline, fact);
          publications.add(result);
          return result;
        }
      }
    }
    throw new IllegalStateException(
        "No physical publication after baseline for event " + probe.path("event_id").textValue());
  }

  void assertTopology(CdcOwner owner, int replication) throws Exception {
    final var actual = description(owner.topic());
    assertEquals(owner == CdcOwner.RISK ? 15 : 3, actual.partitions().size());
    for (var partition : actual.partitions()) {
      assertEquals(replication, partition.replicas().size());
      assertEquals(
          replication, partition.replicas().stream().map(node -> node.id()).distinct().count());
    }
    if (owner == CdcOwner.RISK) {
      final var resource = new ConfigResource(ConfigResource.Type.TOPIC, owner.topic());
      final var configuration =
          metadata
              .describeConfigs(List.of(resource))
              .all()
              .get(10, java.util.concurrent.TimeUnit.SECONDS)
              .get(resource);
      assertEquals(replication == 3 ? "2" : "1", configuration.get("min.insync.replicas").value());
      assertEquals("false", configuration.get("unclean.leader.election.enable").value());
    }
  }

  private TopicDescription description(String topic) {
    try {
      return metadata
          .describeTopics(List.of(topic))
          .allTopicNames()
          .get(10, java.util.concurrent.TimeUnit.SECONDS)
          .get(topic);
    } catch (java.util.concurrent.ExecutionException
        | java.util.concurrent.TimeoutException failure) {
      throw new IllegalStateException("Could not observe current topic partitions", failure);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Topic observation interrupted", interrupted);
    }
  }

  private List<TopicPartition> partitions(CdcOwner owner) {
    return description(owner.topic()).partitions().stream()
        .map(partition -> new TopicPartition(owner.topic(), partition.partition()))
        .toList();
  }

  List<JsonNode> publications() {
    return List.copyOf(publications);
  }

  /** Closes the ephemeral observer within its own deadline. */
  @Override
  public void close() {
    try {
      consumer.close(CloseOptions.timeout(Duration.ofSeconds(5)));
    } finally {
      metadata.close(Duration.ofSeconds(5));
    }
  }
}
