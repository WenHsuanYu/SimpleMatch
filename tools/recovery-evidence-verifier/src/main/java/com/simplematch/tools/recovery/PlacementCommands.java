package com.simplematch.tools.recovery;

import java.io.IOException;
import java.nio.file.Path;

/** Parses the concrete collector operations for placement evidence. */
final class PlacementCommands {
  private PlacementCommands() {}

  static String execute(String[] arguments) throws IOException {
    ReportFields.require(arguments.length >= 2, "Provide a placement operation.");
    return switch (arguments[1]) {
      case "verify" -> {
        count(arguments, 3);
        yield new PlacementRecoveryVerifier().verify(Path.of(arguments[2]));
      }
      case "write" -> {
        count(arguments, 4);
        final Path report = new PlacementReportWriter().write(
            Path.of(arguments[2]), Integer.parseInt(arguments[3]));
        yield new PlacementRecoveryVerifier().verify(report);
      }
      case "redis" -> {
        count(arguments, 4);
        PlacementRecoveryVerifier.verifyRedisSnapshot(
            JsonReports.read(Path.of(arguments[2])), Integer.parseInt(arguments[3]));
        yield "local/redis-snapshot";
      }
      case "snapshot" -> {
        count(arguments, 6);
        yield new PlacementSnapshots().normalize(Path.of(arguments[2]), Path.of(arguments[3]),
            Path.of(arguments[4]), Integer.parseInt(arguments[5])).toString();
      }
      case "probe" -> {
        count(arguments, 6);
        yield PlacementHealth.observation(arguments[2], arguments[3],
            Integer.parseInt(arguments[4]), Path.of(arguments[5])).toString();
      }
      default -> throw new IllegalArgumentException("Unknown placement operation.");
    };
  }

  private static void count(String[] arguments, int expected) {
    ReportFields.require(arguments.length == expected, "Wrong placement argument count.");
  }
}
