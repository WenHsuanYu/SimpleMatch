package com.simplematch.marketreference.builder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Objects;

/** Reads a previously captured complete official source set for deterministic offline runs. */
public final class DirectoryOfficialSourceTransport implements OfficialSourceTransport {
  private final Path sourceDirectory;
  private final Clock clock;

  /** Creates a transport rooted at an explicitly selected official-source capture directory. */
  public DirectoryOfficialSourceTransport(Path sourceDirectory, Clock clock) {
    final Path directory =
        Objects.requireNonNull(sourceDirectory, "official source directory is required");
    this.sourceDirectory = directory.toAbsolutePath();
    this.clock = Objects.requireNonNull(clock, "clock is required");
  }

  /** Reads the exact named source file while retaining its official endpoint identity. */
  @Override
  public RetrievedOfficialSource retrieve(OfficialSourceType sourceType) {
    Objects.requireNonNull(sourceType, "source type is required");
    final Path sourceFile = sourceDirectory.resolve(sourceType.fixtureFileName());
    try {
      return new RetrievedOfficialSource(
          sourceType, sourceType.endpoint(), clock.instant(), Files.readAllBytes(sourceFile));
    } catch (IOException exception) {
      throw new MarketReferenceBuildException(
          "failed to read official source capture: " + sourceFile, exception);
    }
  }
}
