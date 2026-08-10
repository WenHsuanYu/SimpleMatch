package com.simplematch.marketreference.builder;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

/**
 * Retains preliminary review outputs and approved final artifacts in non-overlapping paths.
 */
public final class ApprovedArtifactStore {
  private static final String ARTIFACT_FILE_NAME = "market_reference.json";
  private static final String CHECKSUM_FILE_NAME = "market_reference.sha256";
  private static final String APPROVAL_REPORT_FILE_NAME = "approval-report.json";
  private final ObjectMapper reportMapper;

  /** Creates a deterministic report writer for the offline workflow. */
  public ApprovedArtifactStore(ObjectMapper objectMapper) {
    this.reportMapper =
        Objects.requireNonNull(objectMapper, "object mapper is required")
            .copy()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
  }

  /**
   * Writes a preliminary candidate outside the approved delivery tree.
   *
   * @param outputDirectory explicitly chosen candidate output directory
   * @param candidate non-deployable D-1 candidate
   */
  public void writeCandidate(Path outputDirectory, CandidateArtifact candidate) {
    Objects.requireNonNull(outputDirectory, "candidate output directory is required");
    Objects.requireNonNull(candidate, "candidate artifact is required");
    final Path candidateDirectory =
        outputDirectory
            .resolve("preliminary")
            .resolve(candidate.artifact().metadata().tradingDay().toString());
    try {
      Files.createDirectories(candidateDirectory);
      writeBytes(
          candidateDirectory.resolve("preliminary_market_reference_candidate.json"),
          candidate.artifactBytes());
      writeText(
          candidateDirectory.resolve("candidate-content.sha256"),
          candidate.contentSha256() + '\n');
      writeJson(candidateDirectory.resolve("candidate-review.json"), candidate.reviewSummary());
    } catch (IOException exception) {
      throw new MarketReferenceBuildException("failed to retain preliminary candidate", exception);
    }
  }

  /**
   * Writes exactly one approved final artifact directory and refuses to overwrite prior evidence.
   *
   * @param approvedRoot configured {@code config/market-reference/approved} directory
   * @param finalArtifact fully verified operator-approved final output
   */
  public void writeFinal(Path approvedRoot, FinalArtifact finalArtifact) {
    Objects.requireNonNull(approvedRoot, "approved artifact root is required");
    Objects.requireNonNull(finalArtifact, "final artifact is required");
    final Path target = approvedRoot.resolve(finalArtifact.identity().tradingDay().toString());
    if (Files.exists(target)) {
      throw new MarketReferenceBuildException("approved artifact already exists for trading day");
    }
    try {
      Files.createDirectories(approvedRoot);
      Files.createDirectory(target);
      writeFinalContent(target, finalArtifact);
    } catch (IOException exception) {
      throw new MarketReferenceBuildException(
          "failed to retain approved final artifact", exception);
    }
  }

  private void writeFinalContent(Path target, FinalArtifact finalArtifact) throws IOException {
    writeBytes(target.resolve(ARTIFACT_FILE_NAME), finalArtifact.artifactBytes());
    writeText(
        target.resolve(CHECKSUM_FILE_NAME), finalArtifact.identity().contentSha256() + '\n');
    writeJson(
        target.resolve(APPROVAL_REPORT_FILE_NAME),
        ApprovalReportJson.from(finalArtifact.approvalReport()));
    final Path deliveryDirectory = Files.createDirectories(target.resolve("delivery"));
    writeText(deliveryDirectory.resolve("manifest.yaml"), finalArtifact.deliveryPlan().manifest());
    if (finalArtifact.deliveryPlan().deliveryType() == ArtifactDeliveryType.OCI_DATA_IMAGE) {
      writeOciDataImageContract(deliveryDirectory, finalArtifact);
    }
  }

  private void writeOciDataImageContract(Path deliveryDirectory, FinalArtifact finalArtifact)
      throws IOException {
    final Path ociDataImageDirectory =
        Files.createDirectories(deliveryDirectory.resolve("oci-data-image"));
    final Path payload = Files.createDirectories(ociDataImageDirectory.resolve("payload"));
    writeBytes(payload.resolve(ARTIFACT_FILE_NAME), finalArtifact.artifactBytes());
    writeText(payload.resolve(CHECKSUM_FILE_NAME), finalArtifact.identity().contentSha256() + '\n');
    writeText(
        ociDataImageDirectory.resolve("Containerfile"),
        """
        # OCI_DATA_IMAGE_BASE must be a digest-pinned image with /bin/sh and cp.
        ARG OCI_DATA_IMAGE_BASE
        FROM ${OCI_DATA_IMAGE_BASE}
        COPY payload/ /payload/
        COPY entrypoint.sh /entrypoint.sh
        ENTRYPOINT ["/entrypoint.sh"]
        """);
    writeText(
        ociDataImageDirectory.resolve("entrypoint.sh"),
        """
        #!/bin/sh
        set -eu
        cp /payload/market_reference.json /market-reference/market_reference.json
        cp /payload/market_reference.sha256 /market-reference/market_reference.sha256
        """);
  }

  private void writeBytes(Path path, byte[] bytes) throws IOException {
    Files.write(path, bytes, StandardOpenOption.CREATE_NEW);
  }

  private void writeText(Path path, String content) throws IOException {
    Files.writeString(path, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
  }

  private void writeJson(Path path, Object value) throws IOException {
    try {
      writeBytes(path, reportMapper.writeValueAsBytes(value));
    } catch (JsonProcessingException exception) {
      throw new MarketReferenceBuildException(
          "failed to serialize artifact review evidence", exception);
    }
  }

  private record ApprovalReportJson(
      int reportVersion,
      String artifactIdentity,
      String tradingDay,
      String contentSha256,
      String approvedBy,
      long approvedAtUnixMs,
      java.util.List<SourceProvenanceJson> sourceProvenance,
      ArtifactReviewSummary reviewSummary) {
    static ApprovalReportJson from(MarketReferenceApprovalReport report) {
      return new ApprovalReportJson(
          report.reportVersion(),
          report.artifactIdentity().value(),
          report.artifactIdentity().tradingDay().toString(),
          report.artifactIdentity().contentSha256(),
          report.approval().approvedBy(),
          report.approval().approvedAtUnixMs(),
          report.sourceProvenance().stream().map(SourceProvenanceJson::from).toList(),
          report.reviewSummary());
    }
  }

  private record SourceProvenanceJson(
      String sourceId,
      String sourceUrl,
      String sourceDate,
      long retrievedAtUnixMs,
      String contentSha256) {
    static SourceProvenanceJson from(com.simplematch.marketreference.SourceProvenance source) {
      return new SourceProvenanceJson(
          source.sourceId(),
          source.sourceUrl(),
          source.sourceDate().toString(),
          source.retrievedAtUnixMs(),
          source.contentSha256());
    }
  }
}
