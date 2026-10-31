package com.simplematch.tools.kubernetes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.nio.file.Files;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class KubernetesInputTest {
  private final Path manifest = Path.of(System.getProperty("verificationRepoRoot"),
      "tools/market-reference-builder/config/market-reference/approved/2026-08-17/delivery/manifest.yaml");

  @Test
  void validatesTheExistingApprovedDeliveryWithoutRelabelingItsHistoricalDay() throws Exception {
    final var input = KubernetesInput.load(manifest, LocalDate.parse("2026-08-17"));
    assertEquals(LocalDate.parse("2026-08-17"), input.artifact().identity().tradingDay());
  }

  @Test
  void refusesARequestedDayThatDiffersFromTheApprovedDelivery() {
    assertThrows(IllegalArgumentException.class,
        () -> KubernetesInput.load(manifest, LocalDate.parse("2026-08-18")));
  }
  @Test
  void deploymentUsesTheValidatedSnapshotAfterTheInputFileChanges(
      @org.junit.jupiter.api.io.TempDir Path temporary) throws Exception {
    final Path selected = temporary.resolve("delivery.yaml");
    Files.copy(manifest, selected);
    final var input = KubernetesInput.load(selected, LocalDate.parse("2026-08-17"));
    final var validated = input.configMap();
    Files.writeString(selected, "replaced after preflight");
    assertEquals(validated, input.configMap());
    input.configMap().put("immutable", false);
    assertEquals(validated, input.configMap());
  }

}
