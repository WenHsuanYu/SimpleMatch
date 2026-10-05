package com.simplematch.riskservice.operations;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Internal HTTP adapter for Risk's verified daily operational identity. */
@RestController
@RequestMapping("/internal/operational-status")
public final class RiskOperationalStatusController {
  private final RiskOperationalStatusProvider provider;

  /** Creates the HTTP adapter over Risk-owned operational facts. */
  public RiskOperationalStatusController(RiskOperationalStatusProvider provider) {
    this.provider = provider;
  }

  /** Returns a fresh Risk observation. */
  @GetMapping
  public RiskOperationalStatus status() {
    return provider.current();
  }
}
