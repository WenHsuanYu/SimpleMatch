package com.simplematch.marketreference.builder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MarketReferenceBuilderMainTest {
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-08-11T00:30:00Z"), ZoneOffset.UTC);

  @DisplayName("candidate CLI runs exclusively from an explicit offline source capture directory")
  @Test
  void writesPreliminaryCandidateFromSourceDirectory(@TempDir Path temporaryDirectory)
      throws IOException {
    final Path sourceDirectory = Files.createDirectory(temporaryDirectory.resolve("sources"));
    copyFixtures(sourceDirectory);
    final Path outputDirectory = temporaryDirectory.resolve("output");

    MarketReferenceBuilderMain.run(
        new String[] {
          "candidate",
          "--trading-day",
          "2026-08-11",
          "--source-dir",
          sourceDirectory.toString(),
          "--output-dir",
          outputDirectory.toString()
        },
        CLOCK);

    assertThat(
            outputDirectory
                .resolve("preliminary/2026-08-11/preliminary_market_reference_candidate.json"))
        .exists();
    assertThat(outputDirectory.resolve("2026-08-11/market_reference.json")).doesNotExist();
  }

  @DisplayName("CLI refuses ambiguous live and directory source selection")
  @Test
  void rejectsBothSourceModes(@TempDir Path temporaryDirectory) {
    assertThatThrownBy(
            () ->
                MarketReferenceBuilderMain.run(
                    new String[] {
                      "candidate",
                      "--trading-day",
                      "2026-08-11",
                      "--source-dir",
                      temporaryDirectory.toString(),
                      "--fetch-live",
                      "--output-dir",
                      temporaryDirectory.resolve("output").toString()
                    },
                    CLOCK))
        .isInstanceOf(MarketReferenceBuildException.class)
        .hasMessageContaining("exactly one");
  }

  private void copyFixtures(Path sourceDirectory) throws IOException {
    for (OfficialSourceType sourceType : OfficialSourceType.values()) {
      final String resourceName = "/official-sources/" + sourceType.fixtureFileName();
      try (InputStream input = getClass().getResourceAsStream(resourceName)) {
        if (input == null) {
          throw new IOException("missing fixture resource: " + resourceName);
        }
        Files.write(sourceDirectory.resolve(sourceType.fixtureFileName()), input.readAllBytes());
      }
    }
  }
}
