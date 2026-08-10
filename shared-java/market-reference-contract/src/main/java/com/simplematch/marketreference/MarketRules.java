package com.simplematch.marketreference;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** The versioned reusable market-rule catalogue embedded in an artifact. */
public record MarketRules(
    String ruleSetVersion,
    String currency,
    List<MarketRule> rules,
    List<TickTableDefinition> tickTables) {
  /** Validates the catalogue and canonicalizes its ordering. */
  public MarketRules {
    if (ruleSetVersion == null || ruleSetVersion.isBlank()) {
      throw new MarketReferenceValidationException("market-rule set version is required");
    }
    if (!"TWD".equals(currency)) {
      throw new MarketReferenceValidationException("Phase 1 market-rule currency must be TWD");
    }
    rules = sortedCopy(rules, Comparator.comparing(MarketRule::ruleId), "market rules");
    tickTables =
        sortedCopy(
            tickTables,
            Comparator.comparing(TickTableDefinition::tickTableId),
            "tick tables");
    if (rules.isEmpty() || tickTables.isEmpty()) {
      throw new MarketReferenceValidationException(
          "market rules and tick tables must not be empty");
    }
  }

  private static <T> List<T> sortedCopy(
      List<T> values, Comparator<T> comparator, String fieldName) {
    return List.copyOf(
        Objects.requireNonNull(values, fieldName + " are required")
            .stream()
            .sorted(comparator)
            .toList());
  }

  /** Returns a defensive immutable copy of reusable market rules. */
  @Override
  public List<MarketRule> rules() {
    return List.copyOf(rules);
  }

  /** Returns a defensive immutable copy of reusable tick tables. */
  @Override
  public List<TickTableDefinition> tickTables() {
    return List.copyOf(tickTables);
  }
}
