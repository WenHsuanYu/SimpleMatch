package com.simplematch.riskservice.admission;

import java.time.Instant;

/** Resolves one authoritative artifact route before an admission enters account work. */
@FunctionalInterface
public interface AdmissionRoutingPolicyResolver {
  /**
   * Selects the daily artifact route and explicit partition for one validated command.
   *
   * @param command validated admission command
   * @param at instant used for effective-interval selection
   * @return artifact identity and partition that must be persisted together
   */
  AdmissionDeliveryRoute resolve(AdmissionCommand command, Instant at);
}
