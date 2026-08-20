package com.simplematch.tools.riskmatchinge2e;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simplematch.tools.riskmatchinge2e.RiskAdmissionProbe.AdmissionObservation;
import com.simplematch.tools.riskmatchinge2e.RiskAdmissionProbe.AdmissionPath;
import io.grpc.Status;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Protects the replay-specific terminal verdict consumed by the restart/replay harness. */
class ReplayEvidenceWriterTest {
  @TempDir Path tempDir;

  @Test
  void writesSynchronousTerminalReplayPass() throws Exception {
    final ObjectMapper json = new ObjectMapper();
    final ReplayEvidenceWriter writer = new ReplayEvidenceWriter(json, tempDir);
    final AdmissionObservation admission =
        new AdmissionObservation(
            AdmissionPath.SYNCHRONOUS_ACCEPTED,
            "00000000-0000-0000-0000-000000000001",
            "00000000-0000-0000-0000-000000000002",
            "00000000-0000-0000-0000-000000000003",
            false,
            0,
            12L,
            Status.Code.OK,
            "ACCEPTED");

    writer.writePass(admission);

    final JsonNode verdict = json.readTree(tempDir.resolve("verifier-verdict.json").toFile());
    assertEquals("PASS", verdict.path("status").asText());
    assertEquals("REPLAY", verdict.path("mode").asText());
    assertEquals("SYNCHRONOUS_ACCEPTED", verdict.path("admissionPath").asText());
    assertEquals("ACCEPTED", verdict.path("terminalStatus").asText());
    assertEquals(0, verdict.path("reconciliationAttempts").asInt());
  }
}
