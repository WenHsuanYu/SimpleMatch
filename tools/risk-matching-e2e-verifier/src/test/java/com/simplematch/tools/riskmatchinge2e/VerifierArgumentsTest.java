package com.simplematch.tools.riskmatchinge2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.simplematch.contracts.common.v2.Side;
import com.simplematch.tools.riskmatchinge2e.VerifierArguments.VerificationMode;
import org.junit.jupiter.api.Test;

/** Protects the initial/replay CLI mode contract used by the two deployed verifier Jobs. */
class VerifierArgumentsTest {
  @Test
  void defaultsToInitialMode() {
    final VerifierArguments arguments = VerifierArguments.parse(baseArguments());

    assertEquals(VerificationMode.INITIAL, arguments.execution().mode());
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
  void acceptsExplicitReplayModeCaseInsensitively() {
    final String[] base = baseArguments();
    final String[] replay = java.util.Arrays.copyOf(base, base.length + 2);
    replay[base.length] = "--mode";
    replay[base.length + 1] = "replay";

    final VerifierArguments arguments = VerifierArguments.parse(replay);

    assertEquals(VerificationMode.REPLAY, arguments.execution().mode());
  }

  @Test
  void rejectsUnknownMode() {
    final String[] base = baseArguments();
    final String[] invalid = java.util.Arrays.copyOf(base, base.length + 2);
    invalid[base.length] = "--mode";
    invalid[base.length + 1] = "restart-ish";

    assertThrows(IllegalArgumentException.class, () -> VerifierArguments.parse(invalid));
  }

  private static String[] baseArguments() {
    return new String[] {
      "--artifact-path", "/tmp/market_reference.json",
      "--checksum-path", "/tmp/market_reference.sha256",
      "--trading-day", "2026-08-17",
      "--account-id", "00000000-0000-0000-0000-000000000001",
      "--run-id", "rm1-replay-test",
      "--evidence-dir", "/tmp/evidence"
    };
  }
}
