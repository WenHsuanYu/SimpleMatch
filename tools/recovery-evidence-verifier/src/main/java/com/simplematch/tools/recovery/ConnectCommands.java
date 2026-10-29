package com.simplematch.tools.recovery;

import java.io.IOException;
import java.nio.file.Path;

/** Parses the Connect collector operations while keeping them in one executable. */
final class ConnectCommands {
  private ConnectCommands() {}

  static String execute(String[] arguments) throws IOException {
    ReportFields.require(arguments.length >= 2, "Provide a Connect operation.");
    return switch (arguments[1]) {
      case "verify" -> {
        count(arguments, 3);
        yield new ConnectRecoveryVerifier().verify(Path.of(arguments[2]));
      }
      case "prerequisites" -> {
        count(arguments, 3);
        new ConnectPrerequisites().verify(Path.of(arguments[2]));
        yield "local/connect-prerequisites";
      }
      case "draft" -> {
        count(arguments, 3);
        yield new ConnectReportDraft().create(Path.of(arguments[2])).toString();
      }
      case "owner" -> {
        count(arguments, 5);
        final var owner = new ConnectOwners().resolve(
            JsonReports.read(Path.of(arguments[2])), JsonReports.read(Path.of(arguments[3])));
        JsonReports.write(Path.of(arguments[4]), owner.json());
        yield "local/connect-task-owner";
      }
      case "reassigned" -> {
        count(arguments, 6);
        final var before = ConnectOwners.Owner.read(JsonReports.read(Path.of(arguments[4])));
        final var after = ConnectOwners.Owner.read(JsonReports.read(Path.of(arguments[5])));
        ConnectOwners.matches(JsonReports.read(Path.of(arguments[2])), before);
        ConnectOwners.matches(JsonReports.read(Path.of(arguments[3])), after);
        ConnectOwners.reassigned(before, after);
        yield "local/connect-task-reassignment";
      }
      default -> throw new IllegalArgumentException("Unknown Connect operation.");
    };
  }

  private static void count(String[] arguments, int expected) {
    ReportFields.require(arguments.length == expected, "Wrong Connect argument count.");
  }
}
