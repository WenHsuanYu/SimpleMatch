package com.simplematch.queryservice.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simplematch.contracts.account.v2.AccountLifecycleEvent;
import com.simplematch.contracts.account.v2.AccountLifecycleState;
import com.simplematch.contracts.common.v2.EventMetadata;
import com.simplematch.contracts.common.v2.TwdNotional;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;

/** Publishes synthetic Account facts only into the isolated Query security Lab's Kafka. */
public final class QueryLifecycleFixtureMain {
  private QueryLifecycleFixtureMain() {}

  /**
   * Publishes two fixture accounts, or checks Producer configuration without a broker.
   *
   * @param arguments private fixture-identities path and baseline, paused or check-configuration
   * @throws Exception if fixture validation or acknowledged Kafka delivery fails
   */
  public static void main(String[] arguments) throws Exception {
    if (arguments.length != 2
        || !(arguments[1].equals("baseline") || arguments[1].equals("paused")
            || arguments[1].equals("check-configuration"))) {
      throw new IllegalArgumentException("Expected fixture identities and an approved batch name");
    }
    final var mapper = new ObjectMapper();
    final var identities = mapper.readTree(Files.readAllBytes(Path.of(arguments[0])));
    final var receipts = mapper.createArrayNode();
    final boolean checkConfiguration = arguments[1].equals("check-configuration");
    final Map<String, Object> configuration = Map.of(
        "bootstrap.servers", checkConfiguration ? "127.0.0.1:1" : "kafka:9092",
        "key.serializer", "org.apache.kafka.common.serialization.StringSerializer",
        "value.serializer", "org.apache.kafka.common.serialization.ByteArraySerializer",
        "acks", "all", "enable.idempotence", true,
        "request.timeout.ms", 10000, "delivery.timeout.ms", 30000);
    try (var producer = new KafkaProducer<String, byte[]>(configuration)) {
      if (checkConfiguration) {
        producer.close(Duration.ZERO);
        return;
      }
      for (int partition = 0; partition < 2; partition++) {
        final String alias = partition == 0 ? "X" : "Y";
        final String accountId = UUID.fromString(identities.path("accounts").path(alias).asText()).toString();
        final long notional = (arguments[1].equals("baseline") ? 1000L : 3000L) + partition * 1000L;
        final String eventId = UUID.randomUUID().toString();
        final var event = AccountLifecycleEvent.newBuilder()
            .setMetadata(EventMetadata.newBuilder().setSchemaVersion("v2").setEventId(eventId)
                .setCreatedAtUnixMs(System.currentTimeMillis()).setSourceService("account-service"))
            .setAccountId(accountId).setOrderId(UUID.randomUUID().toString())
            .setReservationId(UUID.randomUUID().toString())
            .setState(AccountLifecycleState.ACCOUNT_LIFECYCLE_STATE_RESERVED)
            .setReservedNotional(TwdNotional.newBuilder().setUnits(notional)).build();
        final byte[] bytes = event.toByteArray();
        final var delivered = producer.send(new ProducerRecord<>(
            "account.lifecycle", partition, accountId, bytes)).get(35, TimeUnit.SECONDS);
        receipts.addObject().put("account", alias).put("accountId", accountId)
            .put("eventId", eventId).put("partition", delivered.partition())
            .put("offset", delivered.offset()).put("notional", notional)
            .put("payloadSha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
      }
    }
    System.out.println("QUERY_SECURITY_FIXTURE=" + mapper.writeValueAsString(
        Map.of("synthetic", true, "batch", arguments[1], "records", receipts)));
  }
}
