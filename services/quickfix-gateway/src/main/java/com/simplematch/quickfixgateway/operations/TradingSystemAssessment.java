package com.simplematch.quickfixgateway.operations;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Accumulates one immutable Gateway readiness decision while rule groups inspect an observation.
 */
final class TradingSystemAssessment {
  private final TradingIdentity canonicalIdentity;
  private final Instant now;
  private final List<String> interruptions = new ArrayList<>();
  private final List<String> pauses = new ArrayList<>();
  private final List<String> warnings = new ArrayList<>();

  TradingSystemAssessment(TradingIdentity canonicalIdentity, Instant now) {
    this.canonicalIdentity = canonicalIdentity;
    this.now = now;
  }

  void interrupt(String reason) {
    interruptions.add(reason);
  }

  void pause(String reason) {
    pauses.add(reason);
  }

  void warn(String warning) {
    warnings.add(warning);
  }

  TradingIdentity canonicalIdentity() {
    return canonicalIdentity;
  }

  Instant now() {
    return now;
  }

  TradingSystemStatus toStatus() {
    if (!interruptions.isEmpty()) {
      return new TradingSystemStatus(
          TradingReadiness.INTERRUPT_REQUIRED,
          Optional.of(canonicalIdentity),
          interruptions,
          warnings,
          now);
    }
    if (!pauses.isEmpty()) {
      return new TradingSystemStatus(
          TradingReadiness.PAUSE_REQUIRED, Optional.of(canonicalIdentity), pauses, warnings, now);
    }
    return new TradingSystemStatus(
        TradingReadiness.OPEN_ELIGIBLE, Optional.of(canonicalIdentity), List.of(), warnings, now);
  }
}
