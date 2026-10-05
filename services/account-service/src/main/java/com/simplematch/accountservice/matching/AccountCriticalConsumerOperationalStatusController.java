package com.simplematch.accountservice.matching;

import com.simplematch.config.delivery.CriticalConsumerOperationalStatus;
import com.simplematch.config.delivery.CriticalConsumerOperationalStatusProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Internal live-observation adapter for Account's critical consumer. */
@RestController
@RequestMapping("/internal/critical-consumer-status")
public final class AccountCriticalConsumerOperationalStatusController {
  private final CriticalConsumerOperationalStatusProvider provider;
  private final AccountFinalMatchingEventStatus consumerStatus;

  /** Creates the HTTP adapter over Account-owned durable quarantine evidence. */
  public AccountCriticalConsumerOperationalStatusController(
      CriticalConsumerOperationalStatusProvider provider,
      AccountFinalMatchingEventStatus consumerStatus) {
    this.provider = provider;
    this.consumerStatus = consumerStatus;
  }

  /** Returns Account's current durable quarantine observation. */
  @GetMapping
  public CriticalConsumerOperationalStatus status() {
    return provider.current(
        consumerStatus.committedOffsets(), consumerStatus.oldestUnprocessedAgeMillis());
  }
}
