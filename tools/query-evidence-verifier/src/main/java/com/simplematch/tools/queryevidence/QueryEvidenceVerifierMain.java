package com.simplematch.tools.queryevidence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Command-line interface for the retained Query evidence verdict. */
public final class QueryEvidenceVerifierMain {
  private QueryEvidenceVerifierMain() {}

  /**
   * Writes the verdict and exits with zero for PASS, one for FAIL, or two for invalid input.
   *
   * @param arguments evidence directory and destination file, or {@code --help}
   */
  public static void main(String[] arguments) {
    if (arguments.length == 1 && arguments[0].equals("--help")) {
      System.out.println("Usage: query-evidence-verifier EVIDENCE_DIRECTORY VERDICT_FILE");
      return;
    }
    if (arguments.length != 2) {
      System.err.println("Provide the evidence directory and the verdict destination.");
      System.exit(2);
      return;
    }
    try {
      final Path destination = Path.of(arguments[1]);
      Files.deleteIfExists(destination);
      final JsonNode verdict = new QueryEvidenceVerifier().evaluate(Path.of(arguments[0]));
      new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(destination.toFile(), verdict);
      System.exit(verdict.path("status").asText().equals("PASS") ? 0 : 1);
    } catch (IOException | IllegalArgumentException exception) {
      System.err.println("Query evidence evaluation failed: " + exception.getMessage());
      System.exit(2);
    }
  }
}
