package com.simplematch.persistence.kafka;

import com.simplematch.config.delivery.CriticalConsumerOperationalStatus;
import com.simplematch.config.delivery.CriticalConsumerOperationalStatusProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Internal live-observation adapter for Persistence's critical consumer. */
@RestController
@RequestMapping("/internal/critical-consumer-status")
public final class PersistenceCriticalConsumerOperationalStatusController {
  private final CriticalConsumerOperationalStatusProvider provider;
  private final PersistenceMatchingEventStatus consumerStatus;

  /** Creates the HTTP adapter over Persistence-owned durable quarantine evidence. */
  public PersistenceCriticalConsumerOperationalStatusController(
      CriticalConsumerOperationalStatusProvider provider,
      PersistenceMatchingEventStatus consumerStatus) {
    this.provider = provider;
    this.consumerStatus = consumerStatus;
  }

  /** Returns Persistence's current durable quarantine observation. */
  @GetMapping
  public CriticalConsumerOperationalStatus status() {
    return provider.current(
        consumerStatus.committedOffsets(), consumerStatus.oldestUnprocessedAgeMillis());
  }
}
