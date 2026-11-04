package com.simplematch.tools.kubernetes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simplematch.contracts.account.v2.AccountLifecycleEvent;
import com.simplematch.contracts.account.v2.AccountLifecycleState;
import com.simplematch.contracts.common.v2.EventMetadata;
import com.simplematch.contracts.common.v2.TwdNotional;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Publishes two selected synthetic Account events using the Query image's actual Kafka client.
 * This compiled test helper is mounted only into one run-owned fixture Job.
 */
public final class KubernetesPausedAccountPublisher {
  private KubernetesPausedAccountPublisher() {}

  /**
   * Delivers Kafka events with broker acknowledgments and prints only non-credential receipts.
   *
   * @param arguments one exact input path provisioned by the owning Kubernetes verifier
   * @throws Exception if the fixture, Kafka client, or broker acknowledgment is invalid
   */
  public static void main(String[] arguments) throws Exception {
    if (arguments.length != 1 || !Files.isRegularFile(Path.of(arguments[0]))) {
      throw new IllegalArgumentException("one readable paused-Account fixture is required");
    }
    final var mapper = new ObjectMapper();
    final var seeds = seeds(mapper.readTree(Path.of(arguments[0]).toFile()));
    final Map<String, Object> options = Map.of(
        "bootstrap.servers", "kafka:9092",
        "key.serializer", "org.apache.kafka.common.serialization.StringSerializer",
        "value.serializer", "org.apache.kafka.common.serialization.ByteArraySerializer",
        "acks", "all", "enable.idempotence", true,
        "request.timeout.ms", 10000, "delivery.timeout.ms", 30000);
    // Kafka clients are owned by the selected Query Boot image; test compilation adds no
    // separate client library or alternate runtime image to the Kubernetes test module.
    final Class<?> producerClass = Class.forName("org.apache.kafka.clients.producer.KafkaProducer");
    final Class<?> recordClass = Class.forName("org.apache.kafka.clients.producer.ProducerRecord");
    final var recordConstructor = recordClass.getConstructor(
        String.class, Integer.class, Object.class, Object.class);
    final var send = producerClass.getMethod("send", recordClass);
    final var receipts = new ArrayList<Map<String, Object>>();
    try (var producer = (AutoCloseable) producerClass.getConstructor(Map.class).newInstance(options)) {
      for (var seed : seeds) {
        final byte[] payload = payload(seed);
        final Object record = recordConstructor.newInstance(
            "account.lifecycle", Integer.valueOf(seed.partition()), seed.accountId(), payload);
        final Object metadata = ((Future<?>) send.invoke(producer, record))
            .get(35, TimeUnit.SECONDS);
        final int partition = (int) metadata.getClass().getMethod("partition").invoke(metadata);
        final long offset = (long) metadata.getClass().getMethod("offset").invoke(metadata);
        if (partition != seed.partition() || offset < 0) {
          throw new IllegalStateException("Kafka did not acknowledge the selected Account input");
        }
        receipts.add(Map.of("accountId", seed.accountId(), "eventId", seed.eventId(),
            "partition", partition, "offset", offset,
            "reservedNotionalUnits", seed.reservedNotionalUnits(),
            "payloadSha256", HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(payload))));
      }
    }
    System.out.println("QUERY_PAUSED_ACCOUNT_RECEIPTS=" + mapper.writeValueAsString(
        Map.of("synthetic", true, "records", receipts)));
  }

  static List<Seed> seeds(JsonNode input) {
    final JsonNode rows = input.path("seeds");
    if (!rows.isArray() || rows.size() != 2) {
      throw new IllegalArgumentException("two paused Account events are required");
    }
    final var result = new ArrayList<Seed>();
    final var accounts = new HashSet<String>();
    final var events = new HashSet<String>();
    for (int partition = 0; partition < 2; partition++) {
      final JsonNode row = rows.get(partition);
      final String account = canonicalUuid(row.path("accountId").asText());
      final String event = canonicalUuid(row.path("eventId").asText());
      final int actualPartition = row.path("partition").asInt(-1);
      final long notional = row.path("reservedNotionalUnits").asLong(-1);
      if (!accounts.add(account) || !events.add(event)
          || actualPartition != partition || notional <= 0) {
        throw new IllegalArgumentException("paused Account fixture contains ambiguous identities");
      }
      result.add(new Seed(account, event, partition, notional));
    }
    return List.copyOf(result);
  }

  private static String canonicalUuid(String raw) {
    if (!UUID.fromString(raw).toString().equals(raw)) {
      throw new IllegalArgumentException("paused Account identity must be canonical");
    }
    return raw;
  }

  static byte[] payload(Seed seed) {
    return AccountLifecycleEvent.newBuilder()
        .setMetadata(EventMetadata.newBuilder().setSchemaVersion("v2")
            .setEventId(seed.eventId()).setCreatedAtUnixMs(System.currentTimeMillis())
            .setSourceService("account-service"))
        .setAccountId(seed.accountId()).setOrderId(UUID.randomUUID().toString())
        .setReservationId(UUID.randomUUID().toString())
        .setState(AccountLifecycleState.ACCOUNT_LIFECYCLE_STATE_RESERVED)
        .setReservedNotional(TwdNotional.newBuilder().setUnits(seed.reservedNotionalUnits()))
        .build().toByteArray();
  }

  record Seed(String accountId, String eventId, int partition, long reservedNotionalUnits) {}
}
