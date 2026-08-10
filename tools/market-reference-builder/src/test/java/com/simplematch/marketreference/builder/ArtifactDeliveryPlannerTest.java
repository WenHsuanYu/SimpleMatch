package com.simplematch.marketreference.builder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.simplematch.marketreference.ArtifactChecksum;
import com.simplematch.marketreference.ArtifactIdentity;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ArtifactDeliveryPlannerTest {
  private final ArtifactDeliveryPlanner planner = new ArtifactDeliveryPlanner();

  @DisplayName("a small artifact uses immutable ConfigMap binary data with the startup path")
  @Test
  void selectsConfigMapForArtifactsAtOrBelowTheFixedLimit() {
    final byte[] content = "{\"market\":\"reference\"}".getBytes(StandardCharsets.UTF_8);
    final String checksum = ArtifactChecksum.sha256(content);

    final ArtifactDeliveryPlan plan =
        planner.plan(identity(checksum), content, checksum, null);

    assertThat(plan.deliveryType()).isEqualTo(ArtifactDeliveryType.CONFIG_MAP);
    assertThat(plan.mountPath()).isEqualTo(ArtifactDeliveryPlanner.ARTIFACT_MOUNT_PATH);
    assertThat(plan.manifest())
        .contains("immutable: true")
        .contains("market_reference.json")
        .contains("market_reference.sha256");
    assertThat(configMapArtifact(plan.manifest())).isEqualTo(content);
  }

  @DisplayName("an oversized artifact requires a digest-pinned OCI data image")
  @Test
  void requiresDigestPinnedOciImageForOversizedArtifacts() {
    final byte[] content = new byte[ArtifactDeliveryPlanner.CONFIG_MAP_MAX_BYTES + 1];
    final String checksum = ArtifactChecksum.sha256(content);

    assertThatThrownBy(() -> planner.plan(identity(checksum), content, checksum, null))
        .isInstanceOf(MarketReferenceBuildException.class)
        .hasMessageContaining("digest-pinned OCI data image");

    final String image = "registry.example/simplematch/market-reference@sha256:" + "b".repeat(64);
    final ArtifactDeliveryPlan plan = planner.plan(identity(checksum), content, checksum, image);

    assertThat(plan.deliveryType()).isEqualTo(ArtifactDeliveryType.OCI_DATA_IMAGE);
    assertThat(plan.manifest())
        .contains(image)
        .contains(ArtifactDeliveryPlanner.ARTIFACT_MOUNT_PATH)
        .contains(checksum);
  }

  private ArtifactIdentity identity(String checksum) {
    return ArtifactIdentity.of(LocalDate.of(2026, 8, 11), checksum);
  }

  private byte[] configMapArtifact(String manifest) {
    final String encoded =
        manifest.lines()
            .filter(line -> line.strip().startsWith("market_reference.json:"))
            .findFirst()
            .orElseThrow()
            .substring("  market_reference.json:".length())
            .strip();
    return Base64.getDecoder().decode(encoded);
  }
}
