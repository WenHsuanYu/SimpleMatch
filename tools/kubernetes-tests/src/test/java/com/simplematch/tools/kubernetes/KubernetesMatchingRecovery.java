package com.simplematch.tools.kubernetes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;

/** Proves recovered command processing and separately controlled Kafka duplicate delivery. */
final class KubernetesMatchingRecovery {
  private final KubernetesBusinessEnvironment.PublicOrder context;
  private final Path fix;
  private final Future<?> client;
  private final ObjectMapper json = new ObjectMapper();
  private final KubernetesPostgres postgres;
  private final long deadline = System.nanoTime() + Duration.ofMinutes(3).toNanos();

  KubernetesMatchingRecovery(KubernetesBusinessEnvironment.PublicOrder context, Path fix,
      Future<?> client) throws Exception {
    this.context = context;
    this.fix = fix;
    this.client = client;
    postgres = new KubernetesPostgres(context.target());
  }

  void verify(KubernetesMatchingEvents events, JsonNode original, JsonNode delivery,
      Map<String, String> originalVariables) throws Exception {
    final var rested = events.observe(original, delivery, "resting");
    replaceOwner(original.required("routingPartition").asInt(),
        rested.required("context").required("sourceInputOffset").asLong());
    context.admission().open(context.target());
    Files.writeString(fix.resolve("cancel-release.next"),
        Long.toString(System.currentTimeMillis() + remaining().toMillis()));
    Files.move(fix.resolve("cancel-release.next"), fix.resolve("cancel-release"),
        StandardCopyOption.ATOMIC_MOVE);
    while (!Files.isRegularFile(fix.resolve("cancel.json"))) {
      remaining();
      if (client.isDone()) {
        client.get();
        throw new IllegalStateException("FIX client stopped before the new cancel completed");
      }
      Thread.sleep(100);
    }
    final var variables = new HashMap<>(originalVariables);
    variables.put("cl_ord_id", "CAN-" + original.required("clOrdId").asText());
    variables.put("exec_type", "4");
    variables.put("partition", original.required("routingPartition").asText());
    final var cancel = await(legacy("resting-buy-admission.sql"), variables,
        value -> "ACCEPTED".equals(value.path("state").asText()));
    assertEquals(1, cancel.path("count").asInt());
    assertEquals(1, cancel.path("outboxCount").asInt());
    assertNotEquals(original.path("commandId"), cancel.path("commandId"));
    assertEquals(original.path("orderId"), cancel.path("orderId"));
    final String deliverySql;
    try (var input = java.util.Objects.requireNonNull(getClass()
        .getResourceAsStream("/resting-buy/gateway-delivery.sql"))) {
      deliverySql = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    }
    final var cancellation = await(deliverySql, variables,
        value -> "SENT".equals(value.path("status").asText()));
    assertEquals(1, cancellation.path("count").asInt());
    final var wire = json.readTree(fix.resolve("cancel.json").toFile());
    assertEquals(cancellation.path("execId"), wire.path("execId"));
    final var cancelledEvent = events.observe(cancel, cancellation, "cancel");
    assertEquals(original.path("quantityShares").asLong(),
        cancelledEvent.path("terminalOrder").path("leavesQuantityShares").asLong());
    variables.put("event_id", cancellation.required("eventId").asText());
    final String durableSql = legacy("resting-buy-durable-state.sql");
    final var cancelled = await(durableSql, variables,
        value -> "RESERVATION_STATUS_RELEASED".equals(value.path("account").path("status").asText())
            && value.path("events").path("persistenceCount").asInt() == 1);
    assertCancelled(cancelled);
    final var repeated = events.redeliver(cancel, cancellation);
    final long offset = repeated.required("observedOffset").asLong();
    await(legacy("matching-recovery-progress.sql"), variables,
        value -> List.of("persistence", "account", "quickfix").stream().allMatch(owner ->
            value.path(owner + "LastProcessedOffset").asLong(-1) >= offset));
    final var after = json.readTree(postgres.execute(durableSql, variables));
    save("matching-cancel-state.json", cancelled);
    save("matching-redelivery-state.json", after);
    assertEquals(cancelled, after, "duplicate delivery must not change business state or revisions");
  }

  private void replaceOwner(int partition, long inputOffset) throws Exception {
    final var target = context.target();
    final String name = "matching-" + partition;
    final var before = target.client().read("get", "pod", name, "-o", "json");
    assertEquals(target.run().runId(), before.path("metadata").path("labels")
        .path(OwnedNamespace.OWNER_LABEL).asText());
    final var claimBefore = claim(before);
    target.client().run("", "delete", "pod", name, "--wait=false");
    JsonNode after;
    do {
      remaining();
      after = target.client().find("get", "pod", name, "--ignore-not-found", "-o", "json")
          .orElseGet(json::createObjectNode);
      final boolean ready = java.util.stream.StreamSupport.stream(
          after.path("status").path("conditions").spliterator(), false)
          .anyMatch(value -> "Ready".equals(value.path("type").asText())
              && "True".equals(value.path("status").asText()));
      if (!before.path("metadata").path("uid").equals(after.path("metadata").path("uid"))
          && ready) {
        break;
      }
      Thread.sleep(500);
    } while (true);
    final var claimAfter = claim(after);
    save("matching-recovery-owner.json", json.valueToTree(Map.of(
        "beforePodUid", before.path("metadata").path("uid"),
        "afterPodUid", after.path("metadata").path("uid"),
        "claimUid", claimAfter.path("metadata").path("uid"),
        "volume", claimAfter.path("spec").path("volumeName"))));
    assertEquals(claimBefore.path("metadata").path("uid"), claimAfter.path("metadata").path("uid"));
    assertEquals(claimBefore.path("spec").path("volumeName"), claimAfter.path("spec").path("volumeName"));
    assertEquals(before.path("spec").path("nodeName"), after.path("spec").path("nodeName"));
    awaitReplayed(partition, after, inputOffset);
  }

  private void awaitReplayed(int partition, JsonNode after, long inputOffset) throws Exception {
    final var target = context.target();
    final String name = after.path("metadata").path("name").asText();
    final String ownerUid = after.path("metadata").path("uid").asText();
    final String metrics = java.util.stream.StreamSupport.stream(
        after.path("spec").path("containers").spliterator(), false)
        .filter(value -> "matching".equals(value.path("name").asText()))
        .flatMap(value -> java.util.stream.StreamSupport.stream(value.path("env").spliterator(), false))
        .filter(value -> "MATCHING_METRICS_PATH".equals(value.path("name").asText()))
        .map(value -> value.path("value").asText()).findFirst().orElseThrow();
    do {
      remaining();
      final var runtime = target.client().read("exec", name, "-c", "matching", "--", "cat", metrics);
      if ("READY".equals(runtime.path("runtime_state").asText())
          && "OPEN".equals(runtime.path("partition_state").asText())
          && runtime.path("admission").path("ownership_permitted").asBoolean()
          && runtime.path("admission").path("recovery_complete").asBoolean()
          && runtime.path("highest_contiguous_completed_offset").asLong(-1) >= inputOffset) {
        save("matching-recovery-runtime.json", runtime);
        assertEquals(name + ":" + ownerUid, runtime.path("admission").path("owner_id").asText());
        assertEquals(partition, runtime.path("admission").path("partition_id").asInt());
        return;
      }
      Thread.sleep(500);
    } while (true);
  }

  private JsonNode claim(JsonNode pod) throws Exception {
    final String name = java.util.stream.StreamSupport.stream(
        pod.path("spec").path("volumes").spliterator(), false)
        .filter(value -> "matching-baseline".equals(value.path("name").asText()))
        .map(value -> value.path("persistentVolumeClaim").path("claimName").asText())
        .findFirst().orElseThrow();
    return context.target().client().read("get", "pvc", name, "-o", "json");
  }

  private JsonNode await(String sql, Map<String, String> variables, Predicate<JsonNode> ready)
      throws Exception {
    do {
      remaining();
      final String output = postgres.execute(sql, variables);
      if (!output.isBlank()) {
        final var value = json.readTree(output);
        if (ready.test(value)) {
          return value;
        }
      }
      Thread.sleep(250);
    } while (true);
  }

  private static void assertCancelled(JsonNode durable) {
    final var projection = durable.path("persistence");
    assertEquals("CANCELLED", projection.path("status").asText());
    for (String field : List.of("leavesQuantityShares", "cumulativeQuantityShares", "fillCount")) {
      assertEquals(0, projection.path(field).asLong());
    }
    assertEquals(1, projection.path("projectionCount").asInt());
    final var account = durable.path("account");
    assertEquals(1, account.path("reservationCount").asInt());
    for (String field : List.of("remainingQuantity", "reservedNotional", "limitReservedNotional",
        "limitUtilizedNotional")) {
      assertEquals(0, new BigDecimal(account.path(field).asText()).signum());
    }
    assertTrue(new BigDecimal(account.path("limitAvailableNotional").asText())
        .compareTo(new BigDecimal(account.path("limitTotalNotional").asText())) == 0);
    for (String owner : List.of("persistence", "account", "quickfix")) {
      assertEquals(1, durable.path("events").path(owner + "Count").asInt());
    }
    assertEquals(0, durable.path("events").path("quarantineCount").asInt());
  }

  private String legacy(String name) throws Exception {
    return Files.readString(context.settings().root()
        .resolve("scripts/end-to-end/critical-consumers/sql").resolve(name));
  }

  private void save(String name, JsonNode value) throws Exception {
    Files.writeString(context.target().run().evidence().resolve(name), value.toPrettyString());
  }

  private Duration remaining() throws TimeoutException {
    final long nanos = deadline - System.nanoTime();
    if (nanos <= 0) {
      throw new TimeoutException("Matching business recovery exceeded three minutes");
    }
    return Duration.ofNanos(nanos);
  }
}
