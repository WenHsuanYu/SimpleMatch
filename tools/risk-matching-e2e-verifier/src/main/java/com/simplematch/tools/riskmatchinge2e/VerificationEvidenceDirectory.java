package com.simplematch.tools.riskmatchinge2e;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/** Owns fail-closed preparation of the verifier-local evidence directory. */
final class VerificationEvidenceDirectory {
  private VerificationEvidenceDirectory() {}

  static void prepare(Path evidenceDir) throws IOException {
    Objects.requireNonNull(evidenceDir, "evidence directory is required");
    Files.createDirectories(evidenceDir);
    try (var entries = Files.list(evidenceDir)) {
      if (entries.findAny().isPresent()) {
        throw new IllegalStateException(
            "evidence directory must be empty before RM-1 verification: " + evidenceDir);
      }
    }
  }
}
