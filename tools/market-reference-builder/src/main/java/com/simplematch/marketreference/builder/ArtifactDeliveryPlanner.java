package com.simplematch.marketreference.builder;

import com.simplematch.marketreference.ArtifactChecksum;
import com.simplematch.marketreference.ArtifactIdentity;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;

/** Selects and renders the immutable deployment form for exact final artifact bytes. */
public final class ArtifactDeliveryPlanner {
  /** Conservative ConfigMap binary-data ceiling, below the Kubernetes object limit. */
  public static final int CONFIG_MAP_MAX_BYTES = 900 * 1024;
  /** Consumer-visible path shared by ConfigMap and OCI delivery forms. */
  public static final String ARTIFACT_MOUNT_PATH =
      "/etc/simplematch/market-reference/market_reference.json";
  private static final String CHECKSUM_FILE_NAME = "market_reference.sha256";
  private static final String ARTIFACT_FILE_NAME = "market_reference.json";

  /**
   * Chooses a deployment form only after confirming the supplied checksum identifies exact bytes.
   *
   * @param identity trading-day and external checksum identity
   * @param artifactBytes exact canonical UTF-8 artifact bytes
   * @param externalChecksum separately delivered checksum text
   * @param ociDataImageReference required digest-pinned image for oversized artifacts
   * @return immutable Kubernetes delivery plan
   */
  public ArtifactDeliveryPlan plan(
      ArtifactIdentity identity,
      byte[] artifactBytes,
      String externalChecksum,
      String ociDataImageReference) {
    Objects.requireNonNull(identity, "artifact identity is required");
    Objects.requireNonNull(artifactBytes, "artifact bytes are required");
    ArtifactChecksum.requireCanonical(externalChecksum);
    requireExactIdentity(identity, artifactBytes, externalChecksum);
    return artifactBytes.length <= CONFIG_MAP_MAX_BYTES
        ? configMapPlan(identity, artifactBytes, externalChecksum)
        : ociDataImagePlan(identity, externalChecksum, ociDataImageReference);
  }

  private void requireExactIdentity(
      ArtifactIdentity identity, byte[] artifactBytes, String externalChecksum) {
    final String calculated = ArtifactChecksum.sha256(artifactBytes);
    if (!calculated.equals(externalChecksum) || !identity.contentSha256().equals(calculated)) {
      throw new MarketReferenceBuildException(
          "delivery checksum does not identify the exact canonical artifact bytes");
    }
  }

  private ArtifactDeliveryPlan configMapPlan(
      ArtifactIdentity identity, byte[] artifactBytes, String externalChecksum) {
    final String configMapName =
        "market-reference-"
            + identity.tradingDay().toString().replace("-", "")
            + '-'
            + identity.contentSha256().substring(0, 12);
    final String artifactBase64 = Base64.getEncoder().encodeToString(artifactBytes);
    final String checksumBase64 =
        Base64.getEncoder().encodeToString(externalChecksum.getBytes(StandardCharsets.US_ASCII));
    return new ArtifactDeliveryPlan(
        ArtifactDeliveryType.CONFIG_MAP,
        identity,
        ARTIFACT_MOUNT_PATH,
        configMapManifest(configMapName, artifactBase64, checksumBase64),
        null);
  }

  private ArtifactDeliveryPlan ociDataImagePlan(
      ArtifactIdentity identity, String externalChecksum, String ociDataImageReference) {
    requireDigestPinnedImage(ociDataImageReference);
    return new ArtifactDeliveryPlan(
        ArtifactDeliveryType.OCI_DATA_IMAGE,
        identity,
        ARTIFACT_MOUNT_PATH,
        ociDataImageManifest(ociDataImageReference, externalChecksum),
        ociDataImageReference);
  }

  private void requireDigestPinnedImage(String imageReference) {
    if (imageReference == null
        || !imageReference.matches(".+@sha256:[0-9a-f]{64}")) {
      throw new MarketReferenceBuildException(
          "oversized artifact requires a digest-pinned OCI data image");
    }
  }

  private String configMapManifest(
      String configMapName, String artifactBase64, String checksumBase64) {
    return (String.join(
                "%n",
                "apiVersion: v1",
                "kind: ConfigMap",
                "metadata:",
                "  name: %s",
                "immutable: true",
                "binaryData:",
                "  %s: %s",
                "  %s: %s",
                "---",
                "# Apply this volume and mount to every Risk and Matching workload.",
                "volumes:",
                "  - name: market-reference",
                "    configMap:",
                "      name: %s",
                "      items:",
                "        - key: %s",
                "          path: %s",
                "        - key: %s",
                "          path: %s",
                "containers:",
                "  - name: APPLICATION_CONTAINER_NAME",
                "    volumeMounts:",
                "      - name: market-reference",
                "        mountPath: /etc/simplematch/market-reference",
                "        readOnly: true",
                "    env:",
                "      - name: MARKET_REFERENCE_ARTIFACT_PATH",
                "        value: %s")
            + "%n")
        .formatted(
            configMapName,
            ARTIFACT_FILE_NAME,
            artifactBase64,
            CHECKSUM_FILE_NAME,
            checksumBase64,
            configMapName,
            ARTIFACT_FILE_NAME,
            ARTIFACT_FILE_NAME,
            CHECKSUM_FILE_NAME,
            CHECKSUM_FILE_NAME,
            ARTIFACT_MOUNT_PATH);
  }

  private String ociDataImageManifest(String imageReference, String checksum) {
    return (String.join(
                "%n",
                "# The OCI data image must contain the exact artifact and checksum under /payload.",
                "volumes:",
                "  - name: market-reference",
                "    emptyDir: {}",
                "initContainers:",
                "  - name: market-reference-data",
                "    image: %s",
                "    command:",
                "      - /bin/sh",
                "      - -ec",
                "      - >-",
                "        cp /payload/%s /market-reference/%s &&",
                "        cp /payload/%s /market-reference/%s &&",
                "        test \"$(cat /market-reference/%s)\" = \"%s\"",
                "    volumeMounts:",
                "      - name: market-reference",
                "        mountPath: /market-reference",
                "containers:",
                "  - name: APPLICATION_CONTAINER_NAME",
                "    volumeMounts:",
                "      - name: market-reference",
                "        mountPath: /etc/simplematch/market-reference",
                "        readOnly: true",
                "    env:",
                "      - name: MARKET_REFERENCE_ARTIFACT_PATH",
                "        value: %s")
            + "%n")
        .formatted(
            imageReference,
            ARTIFACT_FILE_NAME,
            ARTIFACT_FILE_NAME,
            CHECKSUM_FILE_NAME,
            CHECKSUM_FILE_NAME,
            CHECKSUM_FILE_NAME,
            checksum,
            ARTIFACT_MOUNT_PATH);
  }
}
