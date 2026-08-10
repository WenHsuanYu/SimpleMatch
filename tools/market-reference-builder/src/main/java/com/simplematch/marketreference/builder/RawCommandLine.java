package com.simplematch.marketreference.builder;

import java.util.Map;

/** Unvalidated command and parsed options before command-specific gates are applied. */
record RawCommandLine(String command, Map<String, String> values) {
  RawCommandLine {
    values = Map.copyOf(values);
  }
}
