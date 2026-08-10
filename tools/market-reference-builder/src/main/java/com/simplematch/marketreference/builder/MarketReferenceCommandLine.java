package com.simplematch.marketreference.builder;

import java.time.LocalDate;
import java.util.Map;

/** Parsed and validated input for the offline Market Reference command surface. */
final class MarketReferenceCommandLine {
  private final String command;
  private final LocalDate tradingDay;
  private final Map<String, String> values;

  private MarketReferenceCommandLine(
      String command, LocalDate tradingDay, Map<String, String> values) {
    this.command = command;
    this.tradingDay = tradingDay;
    this.values = Map.copyOf(values);
  }

  static MarketReferenceCommandLine parse(String[] arguments) {
    final RawCommandLine raw = new MarketReferenceOptionParser().parse(arguments);
    return new MarketReferenceCommandLineValidator().validate(raw);
  }

  String command() {
    return command;
  }

  LocalDate tradingDay() {
    return tradingDay;
  }

  String value(String optionName) {
    return values.get(optionName);
  }

  String requiredValue(String optionName) {
    return new MarketReferenceCommandLineValidator().required(values, optionName);
  }

  static MarketReferenceCommandLine from(
      String command, LocalDate tradingDay, Map<String, String> values) {
    return new MarketReferenceCommandLine(command, tradingDay, values);
  }
}
