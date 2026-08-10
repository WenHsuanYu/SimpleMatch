package com.simplematch.marketreference.builder;

import java.time.LocalDate;
import java.util.Map;

/** Applies command-specific approval and source-selection requirements before build execution. */
final class MarketReferenceCommandLineValidator {
  MarketReferenceCommandLine validate(RawCommandLine raw) {
    validateSourceSelection(raw.values());
    validateCommandRequirements(raw.command(), raw.values());
    return MarketReferenceCommandLine.from(
        raw.command(), parseTradingDay(required(raw.values(), "trading-day")), raw.values());
  }

  String required(Map<String, String> values, String optionName) {
    final String value = values.get(optionName);
    if (value == null || value.isBlank()) {
      throw new MarketReferenceBuildException("missing required option: --" + optionName);
    }
    return value;
  }

  private void validateSourceSelection(Map<String, String> values) {
    final boolean sourceDirectory = values.containsKey("source-dir");
    final boolean fetchLive = values.containsKey("fetch-live");
    if (sourceDirectory == fetchLive) {
      throw new MarketReferenceBuildException(
          "provide exactly one of --source-dir or --fetch-live");
    }
  }

  private void validateCommandRequirements(String command, Map<String, String> values) {
    required(values, "trading-day");
    if (command.equals("candidate")) {
      required(values, "output-dir");
      return;
    }
    required(values, "approved-root");
    required(values, "approved-by");
  }

  private LocalDate parseTradingDay(String value) {
    try {
      return LocalDate.parse(value);
    } catch (RuntimeException exception) {
      throw new MarketReferenceBuildException("--trading-day must be an ISO date", exception);
    }
  }

  static MarketReferenceBuildException usage() {
    return new MarketReferenceBuildException(
        "usage: candidate|final --trading-day YYYY-MM-DD "
            + "(--source-dir DIR|--fetch-live) [options]");
  }
}
