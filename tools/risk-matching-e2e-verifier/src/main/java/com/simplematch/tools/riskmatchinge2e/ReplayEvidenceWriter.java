package com.simplematch.tools.riskmatchinge2e;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simplematch.tools.riskmatchinge2e.RiskAdmissionProbe.AdmissionObservation;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Writes the replay-specific terminal verdict without expanding the initial evidence writer. */
final class ReplayEvidenceWriter {
  private final ObjectMapper json;
  private final Path evidenceDir;

  ReplayEvidenceWriter(ObjectMapper json, Path evidenceDir) {
    this.json = Objects.requireNonNull(json, "JSON mapper is required");
    this.evidenceDir = Objects.requireNonNull(evidenceDir, "evidence directory is required");
  }

  void writePass(AdmissionObservation admission) throws IOException {
    final Map<String, Object> verdict = new LinkedHashMap<>();
    verdict.put("status", "PASS");
    verdict.put("stage", "COMPLETE");
    verdict.put("mode", "REPLAY");
    verdict.put("commandId", admission.commandId());
    verdict.put("admissionPath", admission.path().name());
    verdict.put("terminalStatus", admission.terminalStatus());
    verdict.put("reconciliationAttempts", admission.reconciliationAttempts());
    json.writerWithDefaultPrettyPrinter()
        .writeValue(evidenceDir.resolve("verifier-verdict.json").toFile(), verdict);
  }
}
