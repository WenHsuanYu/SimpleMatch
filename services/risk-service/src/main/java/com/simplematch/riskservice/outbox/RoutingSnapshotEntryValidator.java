package com.simplematch.riskservice.outbox;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

/** Validates and indexes routing-snapshot entries without widening the resolver interface. */
final class RoutingSnapshotEntryValidator {
  private RoutingSnapshotEntryValidator() {}

  static void validate(
      FileRoutingPartitionResolver.RoutingEntry entry, int partitionCount, Path snapshotPath) {
    if (entry == null) {
      throw new IllegalStateException("routing snapshot entry is missing symbol: " + snapshotPath);
    }
    validateSymbol(entry.symbol, snapshotPath);
    validatePartition(entry, partitionCount);
  }

  static void addPartition(
      Map<String, Integer> partitionsBySymbol, FileRoutingPartitionResolver.RoutingEntry entry) {
    final String normalizedSymbol = normalizeSymbol(entry.symbol);
    final Integer previous = partitionsBySymbol.put(normalizedSymbol, entry.kafkaPartitionId);
    if (previous != null) {
      throw new IllegalStateException(
          "routing snapshot contains duplicate symbol " + normalizedSymbol);
    }
  }

  private static void validateSymbol(String symbol, Path snapshotPath) {
    if (symbol == null || symbol.isBlank()) {
      throw new IllegalStateException("routing snapshot entry is missing symbol: " + snapshotPath);
    }
  }

  private static void validatePartition(
      FileRoutingPartitionResolver.RoutingEntry entry, int partitionCount) {
    if (entry.kafkaPartitionId == null) {
      throw new IllegalStateException(
          "routing snapshot entry is missing kafkaPartitionId for symbol " + entry.symbol);
    }
    if (entry.kafkaPartitionId < 0 || entry.kafkaPartitionId >= partitionCount) {
      throw new IllegalStateException(
          "routing snapshot entry has kafkaPartitionId outside range for symbol "
              + entry.symbol
              + ": "
              + entry.kafkaPartitionId);
    }
  }

  private static String normalizeSymbol(String symbol) {
    return symbol.trim().toUpperCase(Locale.ROOT);
  }
}
