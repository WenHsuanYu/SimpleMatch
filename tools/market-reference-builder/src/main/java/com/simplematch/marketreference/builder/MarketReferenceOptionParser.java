package com.simplematch.marketreference.builder;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Parses only supported command-line tokens before the workflow obtains external inputs. */
final class MarketReferenceOptionParser {
  private static final Set<String> SUPPORTED_OPTIONS =
      Set.of(
          "trading-day",
          "source-dir",
          "fetch-live",
          "output-dir",
          "approved-root",
          "approved-by",
          "previous-artifact",
          "oci-data-image");

  RawCommandLine parse(String[] arguments) {
    return new RawCommandLine(command(arguments), optionValues(arguments));
  }

  private String command(String[] arguments) {
    if (arguments == null || arguments.length == 0) {
      throw MarketReferenceCommandLineValidator.usage();
    }
    final String command = arguments[0];
    if (!command.equals("candidate") && !command.equals("final")) {
      throw MarketReferenceCommandLineValidator.usage();
    }
    return command;
  }

  private Map<String, String> optionValues(String[] arguments) {
    final Map<String, String> values = new HashMap<>();
    int index = 1;
    while (index < arguments.length) {
      final ParsedOption option = parsedOption(arguments, index);
      addUnique(values, option);
      index = option.nextIndex();
    }
    return values;
  }

  private ParsedOption parsedOption(String[] arguments, int index) {
    final String name = optionName(arguments[index]);
    return name.equals("fetch-live")
        ? new ParsedOption(name, "true", index + 1)
        : valueOption(arguments, index, name);
  }

  private String optionName(String argument) {
    if (!argument.startsWith("--") || argument.length() == 2) {
      throw MarketReferenceCommandLineValidator.usage();
    }
    final String name = argument.substring(2);
    if (!SUPPORTED_OPTIONS.contains(name)) {
      throw new MarketReferenceBuildException("unknown option: --" + name);
    }
    return name;
  }

  private ParsedOption valueOption(String[] arguments, int index, String name) {
    final int valueIndex = index + 1;
    if (valueIndex >= arguments.length || arguments[valueIndex].startsWith("--")) {
      throw new MarketReferenceBuildException("option requires a value: --" + name);
    }
    return new ParsedOption(name, arguments[valueIndex], valueIndex + 1);
  }

  private void addUnique(Map<String, String> values, ParsedOption option) {
    if (values.putIfAbsent(option.name(), option.value()) != null) {
      throw new MarketReferenceBuildException(
          "option may only be supplied once: --" + option.name());
    }
  }

  private record ParsedOption(String name, String value, int nextIndex) {}
}
