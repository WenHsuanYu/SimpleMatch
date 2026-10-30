package com.simplematch.tools.dependencies;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Checks actual connector configuration against owner and transport requirements. */
class CdcConnectorContractsTest {
  @ParameterizedTest
  @EnumSource(CdcOwner.class)
  void canonicalConnectorPreservesOwnerScopeAndOpaqueTransport(CdcOwner owner) throws Exception {
    final var document =
        new ObjectMapper()
            .readTree(
                FlywayOwners.root()
                    .resolve("deploy/compose/" + owner.service + "-outbox-connector.json")
                    .toFile());
    assertEquals(owner.connector(), document.path("name").asText());
    final var configuration = document.path("config");
    assertTrue(configuration.isObject());
    final Map<String, String> required =
        Map.ofEntries(
            Map.entry("connector.class", "io.debezium.connector.postgresql.PostgresConnector"),
            Map.entry("tasks.max", "1"),
            Map.entry("table.include.list", owner.schema() + ".outbox"),
            Map.entry("key.converter", "org.apache.kafka.connect.storage.StringConverter"),
            Map.entry("key.converter.schemas.enable", "false"),
            Map.entry("value.converter", "org.apache.kafka.connect.converters.ByteArrayConverter"),
            Map.entry("transforms", "outbox"),
            Map.entry("transforms.outbox.type", "io.debezium.transforms.outbox.EventRouter"),
            Map.entry("transforms.outbox.route.by.field", "topic"),
            Map.entry("transforms.outbox.route.topic.replacement", "${routedByValue}"),
            Map.entry("transforms.outbox.table.field.event.id", "event_id"),
            Map.entry("transforms.outbox.table.field.event.key", "message_key"),
            Map.entry("transforms.outbox.table.field.event.payload", "payload"),
            Map.entry("transforms.outbox.table.field.event.timestamp", "created_at"));
    for (var entry : required.entrySet()) {
      assertEquals(entry.getValue(), configuration.path(entry.getKey()).asText(), entry.getKey());
    }
    assertFalse(configuration.has("transforms.outbox.table.field.event.type"));
    final var placement =
        Arrays.asList(
            configuration
                .path("transforms.outbox.table.fields.additional.placement")
                .asText()
                .split(","));
    assertEquals(3, placement.size());
    assertEquals(
        Set.of(
            "kafka_partition_id:partition",
            "headers_json:header:headers_json",
            "payload_type:header:eventType"),
        placement.stream().collect(Collectors.toSet()));
  }
}
