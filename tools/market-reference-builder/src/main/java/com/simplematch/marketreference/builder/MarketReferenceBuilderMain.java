package com.simplematch.marketreference.builder;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simplematch.marketreference.ArtifactChecksum;
import com.simplematch.marketreference.MarketReferenceArtifact;
import com.simplematch.marketreference.MarketReferenceArtifactCodec;
import com.simplematch.marketreference.MarketReferenceArtifactValidator;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;

/** Command-line entry point for the non-runtime candidate and final Market Reference workflow. */
public final class MarketReferenceBuilderMain {
  private MarketReferenceBuilderMain() {}

  /** Runs the requested offline build command and reports a fail-closed error on stderr. */
  public static void main(String[] arguments) {
    try {
      run(arguments, Clock.systemUTC());
    } catch (MarketReferenceBuildException exception) {
      System.err.println("Market Reference build failed: " + exception.getMessage());
      System.exit(2);
    }
  }

  static void run(String[] arguments, Clock clock) {
    final MarketReferenceCommandLine commandLine = MarketReferenceCommandLine.parse(arguments);
    final ObjectMapper mapper = new ObjectMapper();
    final DailyMarketReferenceWorkflow workflow =
        new DailyMarketReferenceWorkflow(mapper, clock);
    final OfficialMarketDataSources sources = sources(commandLine, clock);
    final MarketReferenceArtifact previousArtifact = previousArtifact(commandLine, mapper);
    if (commandLine.command().equals("candidate")) {
      writeCandidate(commandLine, workflow, sources, previousArtifact, mapper);
      return;
    }
    writeFinal(commandLine, workflow, sources, previousArtifact, mapper, clock);
  }

  private static OfficialMarketDataSources sources(
      MarketReferenceCommandLine commandLine, Clock clock) {
    final OfficialSourceTransport transport;
    if (commandLine.value("source-dir") != null) {
      transport =
          new DirectoryOfficialSourceTransport(
              Path.of(commandLine.value("source-dir")), clock);
    } else {
      transport = new HttpOfficialSourceTransport(HttpClient.newHttpClient(), clock);
    }
    return new OfficialMarketDataClient(transport).retrieveAll();
  }

  private static MarketReferenceArtifact previousArtifact(
      MarketReferenceCommandLine commandLine, ObjectMapper mapper) {
    final String configuredPath = commandLine.value("previous-artifact");
    if (configuredPath == null) {
      return null;
    }
    final Path artifactPath = Path.of(configuredPath);
    final Path checksumPath = artifactPath.resolveSibling("market_reference.sha256");
    try {
      final byte[] bytes = Files.readAllBytes(artifactPath);
      final String checksum = Files.readString(checksumPath, StandardCharsets.US_ASCII).trim();
      ArtifactChecksum.requireCanonical(checksum);
      if (!ArtifactChecksum.sha256(bytes).equals(checksum)) {
        throw new MarketReferenceBuildException("previous artifact checksum does not match bytes");
      }
      final MarketReferenceArtifact artifact =
          new MarketReferenceArtifactCodec(mapper).read(bytes);
      new MarketReferenceArtifactValidator()
          .validateFinal(artifact, artifact.metadata().tradingDay());
      return artifact;
    } catch (java.io.IOException exception) {
      throw new MarketReferenceBuildException(
          "failed to read previous approved artifact", exception);
    }
  }

  private static void writeCandidate(
      MarketReferenceCommandLine commandLine,
      DailyMarketReferenceWorkflow workflow,
      OfficialMarketDataSources sources,
      MarketReferenceArtifact previousArtifact,
      ObjectMapper mapper) {
    final CandidateArtifact candidate =
        workflow.createCandidate(sources, commandLine.tradingDay(), previousArtifact);
    new ApprovedArtifactStore(mapper)
        .writeCandidate(Path.of(commandLine.requiredValue("output-dir")), candidate);
    System.out.println(
        "Wrote non-deployable candidate with checksum " + candidate.contentSha256());
  }

  private static void writeFinal(
      MarketReferenceCommandLine commandLine,
      DailyMarketReferenceWorkflow workflow,
      OfficialMarketDataSources sources,
      MarketReferenceArtifact previousArtifact,
      ObjectMapper mapper,
      Clock clock) {
    final FinalArtifact finalArtifact =
        workflow.createFinal(
            sources,
            commandLine.tradingDay(),
            previousArtifact,
            new OperatorApproval(commandLine.requiredValue("approved-by"), clock.millis()),
            commandLine.value("oci-data-image"));
    new ApprovedArtifactStore(mapper)
        .writeFinal(Path.of(commandLine.requiredValue("approved-root")), finalArtifact);
    System.out.println("Wrote approved final artifact " + finalArtifact.identity().value());
  }

}
