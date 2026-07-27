package com.simplematch.marketdatapublisher.input;

import com.simplematch.marketdatapublisher.snapshot.MarketSnapshotImportService;
import com.simplematch.marketdatapublisher.snapshot.MarketSnapshotValidationException;
import com.simplematch.marketdatapublisher.snapshot.PreparedMarketSnapshot;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.function.Supplier;

/** Replays archived source bytes for deterministic local and test publication runs. */
public final class FixtureReplayMarketSnapshotInput implements MarketSnapshotInput {
  private final MarketSnapshotImportService importer;
  private final Supplier<InputStream> source;

  /** Creates an offline replay input backed by a fresh fixture stream for each invocation. */
  public FixtureReplayMarketSnapshotInput(
      MarketSnapshotImportService importer, Supplier<InputStream> source) {
    this.importer = Objects.requireNonNull(importer, "importer");
    this.source = Objects.requireNonNull(source, "source");
  }

  /** Reads, closes, and normalizes one archived source fixture. */
  @Override
  public PreparedMarketSnapshot nextSnapshot() {
    final InputStream input = source.get();
    if (input == null) {
      throw new MarketSnapshotValidationException("replay fixture is required");
    }
    try (input) {
      return importer.prepare(input.readAllBytes());
    } catch (IOException exception) {
      throw new MarketSnapshotValidationException("failed to read replay fixture", exception);
    }
  }
}
