package com.simplematch.marketreference.builder;

import com.simplematch.marketreference.ArtifactIdentity;
import java.util.Objects;

/** Immutable deployment manifest content for a fully validated final artifact. */
public record ArtifactDeliveryPlan(
    ArtifactDeliveryType deliveryType,
    ArtifactIdentity artifactIdentity,
    String mountPath,
    String manifest,
    String ociDataImageReference) {
  /** Requires a delivery choice, stable identity, shared mount path, and rendered manifest. */
  public ArtifactDeliveryPlan {
    Objects.requireNonNull(deliveryType, "delivery type is required");
    Objects.requireNonNull(artifactIdentity, "artifact identity is required");
    if (mountPath == null || mountPath.isBlank()) {
      throw new MarketReferenceBuildException("artifact mount path is required");
    }
    if (manifest == null || manifest.isBlank()) {
      throw new MarketReferenceBuildException("artifact delivery manifest is required");
    }
    if (deliveryType == ArtifactDeliveryType.OCI_DATA_IMAGE
        && (ociDataImageReference == null || ociDataImageReference.isBlank())) {
      throw new MarketReferenceBuildException("OCI delivery requires a data image reference");
    }
  }
}
