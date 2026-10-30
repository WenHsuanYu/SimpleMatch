package com.simplematch.tools.recovery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Owns CDC evidence input, validation and publication for both native and retained collectors. */
final class CdcCommands {
  private CdcCommands() {}

  static String execute(String[] arguments) throws IOException {
    ReportFields.require(arguments.length >= 3, "Provide a CDC operation and input");
    try {
      return dispatch(arguments);
    } catch (IOException invalid) {
      throw new IOException("CDC evidence input or output could not be read or written");
    }
  }

  private static String dispatch(String[] arguments) throws IOException {
    return switch (arguments[1]) {
      case "probe" -> {
        count(arguments, 3);
        CdcPublicationVerifier.validateProbe(read(arguments[2]));
        yield "CDC probe verified";
      }
      case "same-probe" -> {
        count(arguments, 4);
        CdcPublicationVerifier.sameProbe(read(arguments[2]), read(arguments[3]));
        yield "CDC durable probe unchanged";
      }
      case "evidence" -> {
        count(arguments, 3);
        CdcPublicationEvidence.verify(read(arguments[2]));
        yield "CDC publication evidence verified";
      }
      case "baseline" -> {
        count(arguments, 6);
        final ObjectNode scope = JsonReports.object();
        scope
            .put("schema", arguments[3])
            .put("aggregate_type", arguments[4])
            .put("aggregate_id", arguments[5]);
        CdcOutboxProbe.baseline(read(arguments[2]), scope);
        yield "CDC outbox baseline verified";
      }
      case "outbox-probe" -> {
        count(arguments, 4);
        final Path destination = destination(arguments);
        final JsonNode probe = CdcOutboxProbe.select(read(arguments[2]));
        write(destination, probe);
        yield "CDC durable outbox probe captured";
      }
      case "record", "console-record" -> publication(arguments);
      default -> throw new IllegalArgumentException("Unknown CDC operation");
    };
  }

  private static String publication(String[] arguments) throws IOException {
    count(arguments, 6);
    final Path destination = destination(arguments);
    final JsonNode probe = read(arguments[2]);
    final JsonNode baseline =
        snapshot(Files.readString(EvidenceFiles.safeFile(Path.of(arguments[3]))));
    final JsonNode raw = read(arguments[4]);
    final JsonNode record =
        arguments[1].equals("console-record") ? CdcConsoleRecord.read(probe, raw) : raw;
    final JsonNode result = CdcPublicationVerifier.verify(probe, baseline, record);
    CdcPublicationEvidence.verify(result);
    write(destination, result);
    return result.path("partition").asText() + "\t" + result.path("offset").asText();
  }

  private static Path destination(String[] arguments) throws IOException {
    final Path output = EvidenceFiles.outputFile(Path.of(arguments[arguments.length - 1]));
    for (int index = 2; index < arguments.length - 1; index++) {
      ReportFields.require(
          !output.equals(Path.of(arguments[index]).toAbsolutePath().normalize()),
          "CDC output must not overwrite an input");
    }
    Files.deleteIfExists(output);
    return output;
  }

  private static void write(Path destination, JsonNode value) throws IOException {
    final Path parent = destination.getParent();
    if (parent == null) {
      throw new IllegalArgumentException("Missing evidence destination directory");
    }
    final Path pending = Files.createTempFile(parent, ".cdc-", ".json");
    try {
      JsonReports.write(pending, value);
      Files.move(
          pending,
          destination,
          java.nio.file.StandardCopyOption.ATOMIC_MOVE,
          java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    } finally {
      Files.deleteIfExists(pending);
    }
  }

  static ObjectNode snapshot(String source) {
    final ObjectNode result = JsonReports.object();
    for (String line : source.lines().toList()) {
      final String[] fields = line.split("\t", -1);
      ReportFields.require(
          fields.length == 2
              && fields[0].matches("0|[1-9][0-9]*")
              && fields[1].matches("0|[1-9][0-9]*"),
          "Invalid Kafka baseline row");
      ReportFields.require(!result.has(fields[0]), "Duplicate Kafka baseline partition");
      result.put(fields[0], Long.parseLong(fields[1]));
    }
    ReportFields.require(!result.isEmpty(), "Kafka baseline is empty");
    return result;
  }

  private static JsonNode read(String file) throws IOException {
    return JsonReports.read(EvidenceFiles.safeFile(Path.of(file)));
  }

  private static void count(String[] arguments, int expected) {
    ReportFields.require(arguments.length == expected, "Invalid CDC command arguments");
  }
}
