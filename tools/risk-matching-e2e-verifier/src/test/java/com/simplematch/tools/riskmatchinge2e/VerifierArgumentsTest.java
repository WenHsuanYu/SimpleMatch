package com.simplematch.tools.riskmatchinge2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.simplematch.contracts.common.v2.Side;
import org.junit.jupiter.api.Test;

/** Protects the retained deployed command verifier's CLI and retired-mode rejection. */
class VerifierArgumentsTest {
  @Test
  void defaultsToBuySide() {
    final VerifierArguments arguments = VerifierArguments.parse(baseArguments());

    assertEquals(Side.SIDE_BUY, arguments.side());
  }

  @Test
  void acceptsExplicitSellSide() {
    final String[] base = baseArguments();
    final String[] sell = java.util.Arrays.copyOf(base, base.length + 2);
    sell[base.length] = "--side";
    sell[base.length + 1] = "SELL";

    final VerifierArguments arguments = VerifierArguments.parse(sell);

    assertEquals(Side.SIDE_SELL, arguments.side());
  }

  @Test
  void rejectsUnspecifiedOrUnknownSide() {
    final String[] base = baseArguments();
    final String[] unspecified = java.util.Arrays.copyOf(base, base.length + 2);
    unspecified[base.length] = "--side";
    unspecified[base.length + 1] = "SIDE_UNSPECIFIED";
    final String[] unknown = java.util.Arrays.copyOf(base, base.length + 2);
    unknown[base.length] = "--side";
    unknown[base.length + 1] = "BUY_LIMIT";

    assertThrows(IllegalArgumentException.class, () -> VerifierArguments.parse(unspecified));
    assertThrows(IllegalArgumentException.class, () -> VerifierArguments.parse(unknown));
  }

  @Test
  void rejectsBlankSide() {
    final String[] base = baseArguments();
    final String[] blank = java.util.Arrays.copyOf(base, base.length + 2);
    blank[base.length] = "--side";
    blank[base.length + 1] = " ";

    assertThrows(IllegalArgumentException.class, () -> VerifierArguments.parse(blank));
  }

  @Test
  void rejectsRetiredReplayMode() {
    final String[] base = baseArguments();
    final String[] invalid = java.util.Arrays.copyOf(base, base.length + 2);
    invalid[base.length] = "--mode";
    invalid[base.length + 1] = "REPLAY";

    assertThrows(IllegalArgumentException.class, () -> VerifierArguments.parse(invalid));
  }

  private static String[] baseArguments() {
    return new String[] {
      "--artifact-path", "/tmp/market_reference.json",
      "--checksum-path", "/tmp/market_reference.sha256",
      "--trading-day", "2026-08-17",
      "--account-id", "00000000-0000-0000-0000-000000000001",
      "--run-id", "rm1-verifier-test",
      "--evidence-dir", "/tmp/evidence"
    };
  }
}
