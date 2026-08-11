package com.simplematch.queryservice.runtime;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Objects;

/** Installs a verified mounted artifact through the query service's rebuild transaction seam. */
public final class QueryMarketReferenceInstallationService {
  private final QueryMarketReferenceArtifactLoader loader;
  private final QueryProjectionRebuildService rebuildService;
  private final Clock clock;

  /** Creates the artifact installation operation. */
  public QueryMarketReferenceInstallationService(
      QueryMarketReferenceArtifactLoader loader,
      QueryProjectionRebuildService rebuildService,
      Clock clock) {
    this.loader = Objects.requireNonNull(loader, "loader");
    this.rebuildService = Objects.requireNonNull(rebuildService, "rebuildService");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /** Validates and installs one immutable final artifact for the configured trading day. */
  public void install(LocalDate tradingDay) {
    rebuildService.installMarketReference(loader.load(tradingDay), clock.millis());
  }
}
