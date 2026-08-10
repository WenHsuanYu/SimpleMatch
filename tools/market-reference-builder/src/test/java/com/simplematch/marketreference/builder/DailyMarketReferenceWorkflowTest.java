package com.simplematch.marketreference.builder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simplematch.marketreference.ArtifactReleaseState;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DailyMarketReferenceWorkflowTest {
  private static final LocalDate TRADING_DAY = LocalDate.of(2026, 8, 11);
  private static final Instant NOW = Instant.parse("2026-08-11T00:30:00Z");
  private final DailyMarketReferenceWorkflow workflow =
      new DailyMarketReferenceWorkflow(new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC));

  @DisplayName("the D-1 candidate is visibly preliminary and cannot produce a delivery plan")
  @Test
  void createsNonDeployableCandidateWithRoutesButNoPriceFacts() throws IOException {
    final CandidateArtifact candidate = workflow.createCandidate(fixtureSources(), TRADING_DAY, null);

    assertThat(candidate.artifact().metadata().releaseState())
        .isEqualTo(ArtifactReleaseState.PRELIMINARY);
    assertThat(candidate.deliveryPlan()).isNull();
    assertThat(candidate.reviewSummary().validationResults())
        .contains("CONTENT_SHA256_CALCULATED", "CANDIDATE_NOT_DEPLOYABLE")
        .doesNotContain("EXTERNAL_CHECKSUM_VERIFIED");
    assertThat(candidate.artifact().marketSnapshot().instruments())
        .filteredOn(item -> item.eligibility().name().equals("ELIGIBLE"))
        .extracting(item -> item.referencePriceUnits())
        .containsOnlyNulls();
  }

  @DisplayName("only an approved and fully verified final artifact reaches the delivery directory")
  @Test
  void writesApprovedFinalArtifactAndBoundedReviewEvidence(@TempDir Path temporaryDirectory)
      throws IOException {
    final CandidateArtifact candidate = workflow.createCandidate(fixtureSources(), TRADING_DAY, null);
    final FinalArtifact finalArtifact =
        workflow.createFinal(
            fixtureSources(),
            TRADING_DAY,
            candidate.artifact(),
            new OperatorApproval("trading-operator", NOW.toEpochMilli()),
            null);

    new ApprovedArtifactStore(new ObjectMapper()).writeFinal(temporaryDirectory, finalArtifact);
    final Path approved = temporaryDirectory.resolve("2026-08-11");

    assertThat(finalArtifact.deliveryPlan().deliveryType())
        .isEqualTo(ArtifactDeliveryType.CONFIG_MAP);
    assertThat(finalArtifact.approvalReport().reviewSummary().validationResults())
        .contains("EXTERNAL_CHECKSUM_VERIFIED", "DELIVERY_PLAN_VALIDATED")
        .doesNotContain("CANDIDATE_NOT_DEPLOYABLE");
    assertThat(Files.readString(approved.resolve("market_reference.sha256")).trim())
        .isEqualTo(finalArtifact.identity().contentSha256());
    assertThat(Files.readAllBytes(approved.resolve("market_reference.json")))
        .isEqualTo(finalArtifact.artifactBytes());
    assertThat(Files.readString(approved.resolve("approval-report.json")))
        .contains("trading-operator")
        .contains(finalArtifact.identity().contentSha256())
        .contains("validationResults");
  }

  @DisplayName("finalization refuses to create a deployable artifact without operator approval")
  @Test
  void rejectsFinalizationWithoutOperatorApproval() throws IOException {
    assertThatThrownBy(
            () -> workflow.createFinal(fixtureSources(), TRADING_DAY, null, null, null))
        .isInstanceOf(MarketReferenceBuildException.class)
        .hasMessageContaining("operator approval");
  }

  private OfficialMarketDataSources fixtureSources() throws IOException {
    return OfficialSourceFixtures.load(getClass(), NOW);
  }
}
