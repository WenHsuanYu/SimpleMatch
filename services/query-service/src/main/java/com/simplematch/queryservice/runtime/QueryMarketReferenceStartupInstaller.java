package com.simplematch.queryservice.runtime;

import com.simplematch.queryservice.config.QueryServiceProperties;
import java.time.LocalDate;
import java.util.Objects;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

/** Optionally installs the mounted final artifact after migrations and service readiness wiring. */
public final class QueryMarketReferenceStartupInstaller {
  private final QueryMarketReferenceInstallationService installationService;
  private final QueryServiceProperties properties;

  /** Creates the opt-in startup installer. */
  public QueryMarketReferenceStartupInstaller(
      QueryMarketReferenceInstallationService installationService,
      QueryServiceProperties properties) {
    this.installationService = Objects.requireNonNull(installationService, "installationService");
    this.properties = Objects.requireNonNull(properties, "properties");
  }

  /** Installs only when production explicitly supplies a trading day and enables the option. */
  @EventListener(ApplicationReadyEvent.class)
  public void installWhenEnabled() {
    final QueryServiceProperties.MarketReference reference = properties.marketReference();
    if (!reference.installOnStartup()) {
      return;
    }
    if (reference.tradingDay().isBlank()) {
      throw new IllegalStateException("query market-reference trading day is required");
    }
    installationService.install(LocalDate.parse(reference.tradingDay()));
  }
}
