package com.simplematch.tools.riskmatchinge2e;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/** Command-line adapter that retains one public market-data snapshot as JSON evidence. */
public final class MarketDataSnapshotObservationMain {
  private MarketDataSnapshotObservationMain() {}

  /** Observes one snapshot using name/value arguments and writes the configured evidence file. */
  public static void main(String[] args) throws Exception {
    final Map<String, String> values = argumentValues(args);
    final String host = values.getOrDefault("--host", "localhost");
    final int port = positiveInt(values, "--port", 65_535);
    final String venueMic = required(values, "--venue-mic");
    final String symbol = required(values, "--symbol");
    final int timeoutSeconds = positiveInt(values, "--timeout-seconds", 300);
    final Path evidence = Path.of(required(values, "--evidence")).toAbsolutePath().normalize();
    final Path readyFile = optionalPath(values, "--ready-file");
    final MarketDataSnapshotObserver.Observation observation =
        MarketDataSnapshotObserver.observe(
            host,
            port,
            venueMic,
            symbol,
            Duration.ofSeconds(timeoutSeconds),
            () -> createReadyFile(readyFile));
    final Path parent = evidence.getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }
    new ObjectMapper().findAndRegisterModules().writerWithDefaultPrettyPrinter()
        .writeValue(evidence.toFile(), observation);
  }

  private static Map<String, String> argumentValues(String[] args) {
    if (args.length % 2 != 0) {
      throw new IllegalArgumentException("arguments must be name/value pairs");
    }
    final Map<String, String> values = new LinkedHashMap<>();
    for (int index = 0; index < args.length; index += 2) {
      final String name = args[index];
      if (!name.startsWith("--") || values.put(name, args[index + 1]) != null) {
        throw new IllegalArgumentException("invalid or duplicate argument: " + name);
      }
    }
    return values;
  }

  private static String required(Map<String, String> values, String name) {
    final String value = values.get(name);
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " is required");
    }
    return value;
  }

  private static int positiveInt(Map<String, String> values, String name, int maximum) {
    final int value;
    try {
      value = Integer.parseInt(required(values, name));
    } catch (NumberFormatException invalid) {
      throw new IllegalArgumentException(name + " must be an integer", invalid);
    }
    if (value < 1 || value > maximum) {
      throw new IllegalArgumentException(name + " must be between 1 and " + maximum);
    }
    return value;
  }

  private static Path optionalPath(Map<String, String> values, String name) {
    final String value = values.get(name);
    return value == null || value.isBlank() ? null : Path.of(value).toAbsolutePath().normalize();
  }

  private static void createReadyFile(Path readyFile) {
    if (readyFile == null) {
      return;
    }
    try {
      final Path parent = readyFile.getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      Files.writeString(readyFile, "READY\n");
    } catch (Exception failure) {
      throw new IllegalStateException("cannot write market-data observation ready file", failure);
    }
  }
}
