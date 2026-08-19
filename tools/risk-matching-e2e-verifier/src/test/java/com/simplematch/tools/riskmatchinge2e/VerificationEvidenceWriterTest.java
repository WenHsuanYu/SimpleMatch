package com.simplematch.tools.riskmatchinge2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simplematch.tools.riskmatchinge2e.RiskAdmissionProbe.AdmissionObservation;
import com.simplematch.tools.riskmatchinge2e.RiskAdmissionProbe.AdmissionPath;
import com.simplematch.tools.riskmatchinge2e.RiskAdmissionProbe.SubmissionObservation;
import io.grpc.Status;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Protects the normalized Admission evidence contract consumed by the shell harness. */
class VerificationEvidenceWriterTest {
  private static final String COMMAND_ID = "00000000-0000-0000-0000-000000000001";
  private static final String ORDER_ID = "00000000-0000-0000-0000-000000000002";
  private static final String ACCOUNT_ID = "00000000-0000-0000-0000-000000000003";

  @TempDir Path tempDir;

  @Test
  void writesRecoveredAdmissionAsOneNormalizedTerminalOutcome() throws Exception {
    final ObjectMapper json = new ObjectMapper();
    VerificationEvidenceDirectory.prepare(tempDir);
    final VerificationEvidenceWriter writer = new VerificationEvidenceWriter(json, tempDir);

    writer.writeSubmission(
        new SubmissionObservation(Status.Code.UNAVAILABLE, Optional.empty(), 0L, 2_000_000L));
    writer.writeAdmissionOutcome(
        new AdmissionObservation(
            AdmissionPath.RECOVERED_ACCEPTED,
            COMMAND_ID,
            ORDER_ID,
            ACCOUNT_ID,
            true,
            3,
            47_000L,
            Status.Code.UNAVAILABLE,
            "ACCEPTED"));

    final JsonNode submission = json.readTree(tempDir.resolve("admission-submit.json").toFile());
    assertEquals("UNAVAILABLE", submission.path("grpcCode").asText());
    assertEquals("UNCERTAIN", submission.path("synchronousOutcome").asText());

    final JsonNode outcome = json.readTree(tempDir.resolve("admission-outcome.json").toFile());
    assertEquals("RECOVERED_ACCEPTED", outcome.path("path").asText());
    assertEquals(COMMAND_ID, outcome.path("commandId").asText());
    assertEquals("ACCEPTED", outcome.path("terminalStatus").asText());
    assertEquals(3, outcome.path("reconciliationAttempts").asInt());
  }

  @Test
  void refusesToReuseNonEmptyEvidenceDirectory() throws Exception {
    Files.writeString(tempDir.resolve("previous-run.json"), "{}\n");

    assertThrows(
        IllegalStateException.class,
        () -> VerificationEvidenceDirectory.prepare(tempDir));
  }
}
