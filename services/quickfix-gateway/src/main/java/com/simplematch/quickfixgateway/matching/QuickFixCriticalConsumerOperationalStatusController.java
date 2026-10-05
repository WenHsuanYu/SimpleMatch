package com.simplematch.quickfixgateway.matching;

import com.simplematch.config.delivery.CriticalConsumerOperationalStatus;
import com.simplematch.config.delivery.CriticalConsumerOperationalStatusProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Internal live-observation adapter for Gateway's critical consumer. */
@RestController
@ConditionalOnProperty(
    name = "simplematch.quickfix-gateway.data-plane-enabled",
    havingValue = "true",
    matchIfMissing = true)
@RequestMapping("/internal/critical-consumer-status")
public final class QuickFixCriticalConsumerOperationalStatusController {
  private final CriticalConsumerOperationalStatusProvider provider;
  private final QuickFixFinalMatchingEventStatus consumerStatus;

  /** Creates the HTTP adapter over Gateway-owned durable quarantine evidence. */
  public QuickFixCriticalConsumerOperationalStatusController(
      CriticalConsumerOperationalStatusProvider provider,
      QuickFixFinalMatchingEventStatus consumerStatus) {
    this.provider = provider;
    this.consumerStatus = consumerStatus;
  }

  /** Returns Gateway's current durable quarantine observation. */
  @GetMapping
  public CriticalConsumerOperationalStatus status() {
    return provider.current(
        consumerStatus.committedOffsets(), consumerStatus.oldestUnprocessedAgeMillis());
  }
}
