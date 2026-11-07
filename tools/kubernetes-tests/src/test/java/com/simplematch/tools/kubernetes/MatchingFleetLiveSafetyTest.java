package com.simplematch.tools.kubernetes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tests the real external Matching fleet gate against isolated Kubernetes JSON responses. */
class MatchingFleetLiveSafetyTest {
  private static final String DIGEST = "test@sha256:" + "1".repeat(64);
  private final ObjectMapper json = new ObjectMapper();

  @TempDir Path temporary;

  @Test
  void verifiesAllOwnersAndFailsClosedOnIdentityStorageAndTimeDrift() throws Exception {
    final Path script = Path.of(System.getProperty("verificationRepoRoot"),
        "scripts/verify-matching-fleet-live.sh");
    final Path fakeKubectl = createFakeKubectl();
    write("statefulset", Map.of("spec", Map.of("replicas", 15)));
    writePods(DIGEST, false);
    writeLeases(Instant.now().toString());
    writePvcs();
    check(script, fakeKubectl, true, null);

    final Path leaseFile = temporary.resolve("leases.json");
    var leases = (ObjectNode) json.readTree(leaseFile.toFile());
    ((ObjectNode) leases.path("items").get(0).path("spec")).put("holderIdentity",
        "matching-0:old-pod-uid");
    json.writeValue(leaseFile.toFile(), leases);
    check(script, fakeKubectl, false, "different Lease holder");

    writeLeases("2000-01-01T00:00:00Z");
    check(script, fakeKubectl, false, "expired");
    writeLeases(Instant.now().toString());

    writePods("test@sha256:" + "a".repeat(64), false);
    check(script, fakeKubectl, false, "real digest");
    writePods(DIGEST, true);
    check(script, fakeKubectl, false, "distinct nodes");
    writePods(DIGEST, false);

    final Path pvcFile = temporary.resolve("pvcs.json");
    var pvcs = (ObjectNode) json.readTree(pvcFile.toFile());
    ((ObjectNode) pvcs.path("items").get(0).path("spec"))
        .set("accessModes", json.valueToTree(List.of("ReadWriteOnce")));
    json.writeValue(pvcFile.toFile(), pvcs);
    check(script, fakeKubectl, false, "ReadWriteOncePod");

    writePvcs();
    Files.writeString(temporary.resolve("statefulset.json"), "{\"extra\":true}\n",
        java.nio.file.StandardOpenOption.APPEND);
    check(script, fakeKubectl, false, "one JSON document");
  }

  private void writePods(String image, boolean sharedNode) throws Exception {
    final var pods = new ArrayList<Map<String, Object>>();
    for (int i = 0; i < 15; i++) {
      pods.add(Map.of("metadata", Map.of("name", "matching-" + i, "uid", "uid-" + i,
          "labels", Map.of("app.kubernetes.io/name", "matching",
              "apps.kubernetes.io/pod-index", Integer.toString(i))),
          "spec", Map.of("nodeName", sharedNode ? "same-node" : "node-" + i,
              "containers", List.of(Map.of("image", image))),
          "status", Map.of("conditions", List.of(Map.of("type", "Ready", "status", "True")))));
    }
    write("pods", Map.of("items", pods));
  }

  private void writeLeases(String renewTime) throws Exception {
    final var leases = new ArrayList<Map<String, Object>>();
    for (int i = 0; i < 15; i++) {
      leases.add(Map.of("metadata",
          Map.of("name", "matching-partition-" + (i < 10 ? "0" : "") + i),
          "spec", Map.of("holderIdentity", "matching-" + i + ":uid-" + i,
              "leaseDurationSeconds", 15, "renewTime", renewTime)));
    }
    write("leases", Map.of("items", leases));
  }

  private void writePvcs() throws Exception {
    final var pvcs = new ArrayList<Map<String, Object>>();
    for (int i = 0; i < 15; i++) {
      pvcs.add(Map.of("metadata", Map.of("name", "matching-baseline-matching-" + i),
          "spec", Map.of("accessModes", List.of("ReadWriteOncePod")),
          "status", Map.of("phase", "Bound")));
    }
    write("pvcs", Map.of("items", pvcs));
  }

  private void write(String name, Object value) throws Exception {
    json.writeValue(temporary.resolve(name + ".json").toFile(), value);
  }

  private Path createFakeKubectl() throws Exception {
    final Path stub = temporary.resolve("kubectl-fixture");
    Files.writeString(stub, """
        #!/bin/bash
        set -euo pipefail
        case "$*" in
          *"get statefulset "*) cat "$FLEET_FIXTURE/statefulset.json" ;;
          *"get pods "*) cat "$FLEET_FIXTURE/pods.json" ;;
          *"get leases "*) cat "$FLEET_FIXTURE/leases.json" ;;
          *"get pvc "*) cat "$FLEET_FIXTURE/pvcs.json" ;;
          *) exit 3 ;;
        esac
        """);
    Files.setPosixFilePermissions(stub, PosixFilePermissions.fromString("rwx------"));
    return stub;
  }

  private void check(Path script, Path fakeKubectl, boolean accepted, String expected)
      throws Exception {
    final Path output = temporary.resolve("fleet-result.log");
    final ProcessBuilder builder = new ProcessBuilder("bash", script.toString(),
        "--namespace", "synthetic", "--kubectl", fakeKubectl.toString());
    builder.environment().put("FLEET_FIXTURE", temporary.toString());
    builder.redirectErrorStream(true);
    builder.redirectOutput(output.toFile());
    final Process process = builder.start();
    try {
      assertTrue(process.waitFor(10, TimeUnit.SECONDS), "fleet gate did not terminate");
      final String details = Files.readString(output);
      assertEquals(accepted, process.exitValue() == 0, details);
      if (expected != null) {
        assertTrue(details.contains(expected), details);
      }
    } finally {
      process.descendants().forEach(ProcessHandle::destroyForcibly);
      if (process.isAlive()) {
        process.destroyForcibly();
      }
    }
  }
}
