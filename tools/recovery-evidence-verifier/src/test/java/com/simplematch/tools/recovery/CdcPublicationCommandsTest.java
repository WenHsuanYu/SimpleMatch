package com.simplematch.tools.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Verifies the public CDC evidence command against fixed durable and physical-record facts. */
class CdcPublicationCommandsTest {
  @TempDir private Path directory;

  @Test
  void exactPostBaselinePublicationProducesTheExistingEvidenceContract() throws Exception {
    fixture();
    final var result =
        command("record", "probe.json", "baseline.tsv", "record.json", "publication.json");
    assertEquals(0, result.status(), result.output());
    final var evidence =
        new ObjectMapper().readTree(directory.resolve("publication.json").toFile());
    assertEquals(2, evidence.path("schema_version").intValue());
    assertEquals("PASS", evidence.path("status").textValue());
    assertEquals(1, evidence.path("partition").intValue());
    assertEquals(2, evidence.path("offset").longValue());
    assertEquals(7, evidence.path("observed").path("header_count").intValue());
    assertTrue(evidence.path("verification").path("payload_exact").booleanValue());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void headerBytesPreserveValidUtf8AndRejectReplacementCollisions(boolean malformed)
      throws Exception {
    fixture();
    final var mapper = new ObjectMapper();
    final var probe =
        (com.fasterxml.jackson.databind.node.ObjectNode)
            mapper.readTree(directory.resolve("probe.json").toFile());
    final String headers = "{\"note\":\"\ufffd\"}";
    probe.put("headers_json", headers);
    mapper.writeValue(directory.resolve("probe.json").toFile(), probe);
    final var record = mapper.readTree(directory.resolve("record.json").toFile());
    final String encoded =
        java.util.HexFormat.of()
            .formatHex(headers.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    ((com.fasterxml.jackson.databind.node.ObjectNode) record.path("headers").get(1))
        .put("value_hex", malformed ? encoded.replace("efbfbd", "ff") : encoded);
    mapper.writeValue(directory.resolve("record.json").toFile(), record);
    assertEquals(
        malformed ? 1 : 0,
        command("record", "probe.json", "baseline.tsv", "record.json", "publication.json")
            .status());
    assertEquals(!malformed, Files.exists(directory.resolve("publication.json")));
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "topic|\"wrong.topic\"",
        "partition|0",
        "offset|1",
        "timestamp_unix_ms|2001",
        "timestamp_unix_ms|2000.5",
        "key_hex|\"616c7465726564\"",
        "payload_hex|\"73656e736974697665\"",
        "partitions|[0]",
        "partitions|[0,1,2]",
        "partitions|[0,1,1]",
        "headers|[]",
        "offset|-1",
        "offset|9223372036854775808",
        "key_hex|\"zz\""
      })
  void changedPhysicalFactsCannotPublishOrLeaveAnOldPass(String field, String value)
      throws Exception {
    fixture();
    final var mapper = new ObjectMapper();
    final var record =
        (com.fasterxml.jackson.databind.node.ObjectNode)
            mapper.readTree(directory.resolve("record.json").toFile());
    record.set(
        field,
        mapper.readTree(
            value.startsWith("\"") || value.startsWith("[") || value.matches("-?[0-9].*")
                ? value
                : "\"" + value + "\""));
    mapper.writeValue(directory.resolve("record.json").toFile(), record);
    Files.writeString(directory.resolve("publication.json"), "{\"status\":\"PASS\"}");
    final var result =
        command("record", "probe.json", "baseline.tsv", "record.json", "publication.json");
    assertEquals(1, result.status());
    assertFalse(Files.exists(directory.resolve("publication.json")));
    assertFalse(result.output().contains("account-payload-v2"));
    assertFalse(result.output().contains("73656e736974697665"));
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "0|00000000-0000-0000-0000-000000000043",
        "1|private-wrong-headers",
        "2|wrong.Type",
        "3|",
        "4|1",
        "5|mysql",
        "6|not-a-uuid"
      })
  void eachRequiredHeaderMustMatchItsKnownContract(int index, String value) throws Exception {
    fixture();
    final var mapper = new ObjectMapper();
    final var record =
        (com.fasterxml.jackson.databind.node.ObjectNode)
            mapper.readTree(directory.resolve("record.json").toFile());
    ((com.fasterxml.jackson.databind.node.ObjectNode) record.path("headers").get(index))
        .put(
            "value_hex",
            java.util.HexFormat.of()
                .formatHex(
                    (value == null ? "" : value)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    mapper.writeValue(directory.resolve("record.json").toFile(), record);
    final var result =
        command("record", "probe.json", "baseline.tsv", "record.json", "publication.json");
    assertEquals(1, result.status());
    assertFalse(result.output().contains("private-wrong-headers"));
  }

  @Test
  void duplicateAndUnexpectedHeadersFailEvenWhenTheCountRemainsSeven() throws Exception {
    fixture();
    final var mapper = new ObjectMapper();
    final var record =
        (com.fasterxml.jackson.databind.node.ObjectNode)
            mapper.readTree(directory.resolve("record.json").toFile());
    ((com.fasterxml.jackson.databind.node.ObjectNode) record.path("headers").get(6))
        .put("name", "id");
    mapper.writeValue(directory.resolve("record.json").toFile(), record);
    assertEquals(
        1,
        command("record", "probe.json", "baseline.tsv", "record.json", "publication.json")
            .status());
    ((com.fasterxml.jackson.databind.node.ObjectNode) record.path("headers").get(6))
        .put("name", "private-unexpected");
    mapper.writeValue(directory.resolve("record.json").toFile(), record);
    assertEquals(
        1,
        command("record", "probe.json", "baseline.tsv", "record.json", "publication.json")
            .status());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"", "0\t5\n0\t5\n", "0\t-1\n1\t2\n", "0\t5\textra\n", "0\t9223372036854775808\n"})
  void malformedBaselineCannotProduceAPass(String baseline) throws Exception {
    fixture();
    Files.writeString(directory.resolve("baseline.tsv"), baseline);
    assertEquals(
        1,
        command("record", "probe.json", "baseline.tsv", "record.json", "publication.json")
            .status());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "event_id",
        "business_identity",
        "message_key",
        "topic",
        "payload_hex",
        "payload_type",
        "headers_json",
        "payload_sha256",
        "created_at_unix_ms"
      })
  void incompleteDurableFactsCannotPass(String field) throws Exception {
    fixture();
    final var mapper = new ObjectMapper();
    final var probe =
        (com.fasterxml.jackson.databind.node.ObjectNode)
            mapper.readTree(directory.resolve("probe.json").toFile());
    probe.remove(field);
    mapper.writeValue(directory.resolve("probe.json").toFile(), probe);
    assertEquals(1, command("probe", "probe.json").status());
  }

  @Test
  void outputCannotOverwriteInputsOrFollowLinksAndMalformedJsonCannotLeakBytes() throws Exception {
    fixture();
    final String original = Files.readString(directory.resolve("probe.json"));
    assertEquals(
        1, command("record", "probe.json", "baseline.tsv", "record.json", "probe.json").status());
    assertEquals(original, Files.readString(directory.resolve("probe.json")));
    Files.createSymbolicLink(directory.resolve("linked.json"), directory.resolve("probe.json"));
    assertEquals(1, command("probe", "linked.json").status());
    Files.writeString(
        directory.resolve("probe.json"), "{\"payload_hex\":private-sensitive-content}");
    final var result =
        command("record", "probe.json", "baseline.tsv", "record.json", "publication.json");
    assertEquals(1, result.status());
    assertFalse(result.output().contains("private-sensitive-content"));
  }

  @Test
  void consoleAdapterProducesTheSamePublicationFactsAsNativeKafka() throws Exception {
    fixture();
    final var mapper = new ObjectMapper();
    final var record = mapper.readTree(directory.resolve("record.json").toFile());
    final var wire = mapper.createObjectNode();
    wire.put("metadata", "CreateTime:2000\tPartition:1\tOffset:2");
    wire.put("key_console_hex", record.path("key_hex").textValue() + "0a");
    wire.put("value_console_hex", record.path("payload_hex").textValue() + "0a");
    wire.put("current_snapshot", Files.readString(directory.resolve("baseline.tsv")));
    final String separator = "__SIMPLEMATCH_CDC_HEADER__";
    wire.put("header_separator", separator);
    final var headers = new ArrayList<String>();
    for (var header : record.path("headers")) {
      headers.add(
          header.path("name").textValue()
              + ":"
              + new String(
                  java.util.HexFormat.of().parseHex(header.path("value_hex").textValue()),
                  java.nio.charset.StandardCharsets.UTF_8));
    }
    wire.put("headers", String.join(separator, headers));
    mapper.writeValue(directory.resolve("wire.json").toFile(), wire);
    assertEquals(
        0, command("record", "probe.json", "baseline.tsv", "record.json", "native.json").status());
    assertEquals(
        0,
        command("console-record", "probe.json", "baseline.tsv", "wire.json", "console.json")
            .status());
    assertEquals(
        mapper.readTree(directory.resolve("native.json").toFile()),
        mapper.readTree(directory.resolve("console.json").toFile()));
    wire.put("key_console_hex", record.path("key_hex").textValue());
    mapper.writeValue(directory.resolve("wire.json").toFile(), wire);
    assertEquals(
        1,
        command("console-record", "probe.json", "baseline.tsv", "wire.json", "console.json")
            .status());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "event_id",
        "business_identity",
        "reservation_id",
        "account_id",
        "message_key",
        "topic",
        "payload_type",
        "headers_json",
        "aggregate_type",
        "explicit_partition",
        "created_at_unix_ms",
        "payload_hex"
      })
  void recoveryCannotChangeAnyDurableProbeFact(String field) throws Exception {
    fixture();
    final var mapper = new ObjectMapper();
    final var observed =
        (com.fasterxml.jackson.databind.node.ObjectNode)
            mapper.readTree(directory.resolve("probe.json").toFile());
    assertEquals(0, command("same-probe", "probe.json", "probe.json").status());
    switch (field) {
      case "event_id" -> observed.put(field, "00000000-0000-0000-0000-000000000043");
      case "explicit_partition" -> observed.put(field, 0);
      case "created_at_unix_ms" -> observed.put(field, 2001);
      case "payload_hex" -> {
        observed.put(field, "6368616e676564");
        observed.put(
            "payload_sha256",
            java.util.HexFormat.of()
                .formatHex(
                    java.security.MessageDigest.getInstance("SHA-256")
                        .digest("changed".getBytes(java.nio.charset.StandardCharsets.UTF_8))));
      }
      default -> observed.put(field, "changed-private-value");
    }
    mapper.writeValue(directory.resolve("observed.json").toFile(), observed);
    final var result = command("same-probe", "probe.json", "observed.json");
    assertEquals(1, result.status());
    assertFalse(result.output().contains("changed-private-value"));
  }

  @Test
  void historicalOutboxRowsRequireAnExactScopedBaselineAndOneNewEvent() throws Exception {
    fixture();
    final var mapper = new ObjectMapper();
    final var current =
        (com.fasterxml.jackson.databind.node.ObjectNode)
            mapper.readTree(directory.resolve("probe.json").toFile());
    current.remove(List.of("business_identity", "reservation_id", "account_id", "payload_sha256"));
    current.put("aggregate_id", "reservation-42");
    final var old = current.deepCopy().put("event_id", "00000000-0000-0000-0000-000000000041");
    final var request =
        mapper
            .createObjectNode()
            .put("schema", "account_service")
            .put("aggregate_type", "account_reservation")
            .put("aggregate_id", "reservation-42");
    request.putArray("rows").add(old).add(current);
    final var baseline =
        request
            .putObject("baseline")
            .put("schema_version", 1)
            .put("schema", "account_service")
            .put("aggregate_type", "account_reservation")
            .put("aggregate_id", "reservation-42");
    baseline.putArray("event_ids").add(old.path("event_id").textValue());
    mapper.writeValue(directory.resolve("request.json").toFile(), request);
    assertEquals(0, command("outbox-probe", "request.json", "selected.json").status());
    assertEquals(
        "00000000-0000-0000-0000-000000000042",
        mapper.readTree(directory.resolve("selected.json").toFile()).path("event_id").textValue());
    ((com.fasterxml.jackson.databind.node.ArrayNode) request.path("rows"))
        .add(current.deepCopy().put("event_id", "00000000-0000-0000-0000-000000000043"));
    mapper.writeValue(directory.resolve("request.json").toFile(), request);
    assertEquals(1, command("outbox-probe", "request.json", "selected.json").status());
    assertFalse(Files.exists(directory.resolve("selected.json")));
    ((com.fasterxml.jackson.databind.node.ArrayNode) request.path("rows")).remove(2);
    baseline.put("aggregate_id", "different-reservation");
    mapper.writeValue(directory.resolve("request.json").toFile(), request);
    assertEquals(1, command("outbox-probe", "request.json", "selected.json").status());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "schema_version",
        "expected_message_key",
        "expected_payload_sha256",
        "expected_headers_json_sha256",
        "expected_event_type",
        "event_id",
        "offset"
      })
  void publicationReadersRejectContradictoryOrOldReports(String field) throws Exception {
    fixture();
    assertEquals(
        0,
        command("record", "probe.json", "baseline.tsv", "record.json", "publication.json")
            .status());
    assertEquals(0, command("evidence", "publication.json").status());
    final var mapper = new ObjectMapper();
    final var report =
        (com.fasterxml.jackson.databind.node.ObjectNode)
            mapper.readTree(directory.resolve("publication.json").toFile());
    if (field.equals("schema_version")) {
      report.put(field, 1);
    } else if (field.equals("offset")) {
      report.put(field, 3);
    } else {
      report.put(field, "changed");
    }
    mapper.writeValue(directory.resolve("publication.json").toFile(), report);
    assertEquals(1, command("evidence", "publication.json").status());
  }

  private void fixture() throws Exception {
    for (String file : List.of("probe.json", "record.json", "baseline.tsv")) {
      try (var input = getClass().getResourceAsStream("/cdc/" + file)) {
        Files.copy(input, directory.resolve(file));
      }
    }
  }

  private Result command(String operation, String... files) throws Exception {
    final List<String> arguments =
        new ArrayList<>(
            List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp",
                System.getProperty("dependencyVerifierClasspath"),
                RecoveryEvidenceVerifierMain.class.getName(),
                "cdc",
                operation));
    arguments.addAll(List.of(files));
    final Path log = Files.createTempFile(directory, "command-", ".log");
    final Process process =
        new ProcessBuilder(arguments)
            .directory(directory.toFile())
            .redirectErrorStream(true)
            .redirectOutput(log.toFile())
            .start();
    try {
      assertTrue(process.waitFor(10, TimeUnit.SECONDS));
      return new Result(process.exitValue(), Files.readString(log));
    } finally {
      if (process.isAlive()) {
        process.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
      }
    }
  }

  private record Result(int status, String output) {}
}
