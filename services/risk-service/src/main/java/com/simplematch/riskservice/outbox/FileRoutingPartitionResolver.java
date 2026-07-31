package com.simplematch.riskservice.outbox;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Resolves symbol partitions from a validated routing-snapshot file. */
public final class FileRoutingPartitionResolver implements RoutingPartitionResolver {
  private final Map<String, Integer> partitionsBySymbol;
  private final int partitionCount;

  private FileRoutingPartitionResolver(
      Map<String, Integer> partitionsBySymbol, int partitionCount) {
    this.partitionsBySymbol = Map.copyOf(partitionsBySymbol);
    this.partitionCount = partitionCount;
  }

  /** Loads and validates a routing snapshot for the supplied partition count. */
  public static FileRoutingPartitionResolver load(
      ObjectMapper objectMapper, Path snapshotPath, int partitionCount) {
    Objects.requireNonNull(objectMapper, "objectMapper");
    Objects.requireNonNull(snapshotPath, "snapshotPath");
    if (partitionCount <= 0) {
      throw new IllegalArgumentException("partitionCount must be positive");
    }

    try {
      final RoutingSnapshot snapshot =
          objectMapper.readValue(readSnapshot(snapshotPath), RoutingSnapshot.class);
      return new FileRoutingPartitionResolver(
          partitionsBySymbol(snapshot.entries(), partitionCount, snapshotPath), partitionCount);
    } catch (IOException exception) {
      throw new IllegalStateException(
          "failed to load routing snapshot from " + snapshotPath, exception);
    }
  }

  @Override
  public int resolve(String symbol) {
    if (symbol == null || symbol.isBlank()) {
      return 0;
    }

    final Integer configuredPartition = partitionsBySymbol.get(normalizeSymbol(symbol));
    if (configuredPartition != null) {
      return configuredPartition;
    }
    return Math.floorMod(symbol.hashCode(), partitionCount);
  }

  private static Map<String, Integer> partitionsBySymbol(
      List<RoutingEntry> entries, int partitionCount, Path snapshotPath) {
    final Map<String, Integer> partitionsBySymbol = new HashMap<>();
    if (entries == null) {
      return partitionsBySymbol;
    }

    for (RoutingEntry entry : entries) {
      RoutingSnapshotEntryValidator.validate(entry, partitionCount, snapshotPath);
      RoutingSnapshotEntryValidator.addPartition(partitionsBySymbol, entry);
    }
    return partitionsBySymbol;
  }

  private static String normalizeSymbol(String symbol) {
    return symbol.trim().toUpperCase(Locale.ROOT);
  }

  private static InputStream readSnapshot(Path snapshotPath) throws IOException {
    final String path = snapshotPath.toString();
    if (path.startsWith("classpath:")) {
      final String resourcePath = path.substring("classpath:".length()).replaceFirst("^/", "");
      final InputStream inputStream =
          FileRoutingPartitionResolver.class.getClassLoader().getResourceAsStream(resourcePath);
      if (inputStream == null) {
        throw new IOException("classpath resource not found: " + resourcePath);
      }
      return inputStream;
    }

    return Files.newInputStream(resolveSnapshotPath(snapshotPath));
  }

  private static Path resolveSnapshotPath(Path snapshotPath) {
    if (snapshotPath.isAbsolute()) {
      return snapshotPath.normalize();
    }

    final Path workspaceRoot = findWorkspaceRoot();
    return workspaceRoot.resolve(snapshotPath).normalize();
  }

  private static Path findWorkspaceRoot() {
    Path current = Path.of("").toAbsolutePath().normalize();
    while (current != null) {
      if (Files.exists(current.resolve("settings.gradle.kts"))
          || Files.exists(current.resolve(".git"))) {
        return current;
      }
      current = current.getParent();
    }
    return Path.of("").toAbsolutePath().normalize();
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  static final class RoutingSnapshot {
    private List<RoutingEntry> entries = List.of();

    public List<RoutingEntry> entries() {
      return entries;
    }

    public void setEntries(List<RoutingEntry> entries) {
      if (entries != null) {
        this.entries = entries;
      }
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  static final class RoutingEntry {
    String symbol;
    Integer kafkaPartitionId;

    public void setSymbol(String symbol) {
      this.symbol = symbol;
    }

    public void setKafkaPartitionId(Integer kafkaPartitionId) {
      this.kafkaPartitionId = kafkaPartitionId;
    }
  }
}
