package com.simplematch.tools.recovery;

import java.io.IOException;
import java.nio.file.Path;

/** Command interface for local recovery evidence. */
public final class RecoveryEvidenceVerifierMain {
  private RecoveryEvidenceVerifierMain() {}

  /**
   * Prints the checked local scope, or rejects the input with a nonzero exit status.
   *
   * @param arguments operation and its evidence files, or {@code --help}
   */
  public static void main(String[] arguments) {
    if (arguments.length == 1 && arguments[0].equals("--help")) {
      System.out.println("Usage: recovery-evidence-verifier OPERATION EVIDENCE_FILES");
      return;
    }
    try {
      ReportFields.require(arguments.length > 0, "Provide a recovery operation.");
      final String result = switch (arguments[0]) {
        case "dependency" -> {
          ReportFields.require(arguments.length == 3, "Provide a dependency and report file.");
          yield new DependencyRecoveryVerifier().verify(arguments[1], Path.of(arguments[2]));
        }
        case "placement" -> PlacementCommands.execute(arguments);
        case "connect" -> ConnectCommands.execute(arguments);
        case "market-data" -> {
          ReportFields.require(
              arguments.length == 3, "Provide Market Data evidence and verdict paths.");
          yield new MarketDataEvidenceVerifier().write(
              Path.of(arguments[1]), Path.of(arguments[2]));
        }
        default -> throw new IllegalArgumentException("Unknown recovery operation.");
      };
      System.out.println(result);
    } catch (IOException | IllegalArgumentException failure) {
      System.err.println("Recovery evidence rejected: " + failure.getMessage());
      System.exit(1);
    }
  }
}
