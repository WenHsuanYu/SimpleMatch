package com.simplematch.riskservice.admission;

import java.time.Instant;

/** Resolves one authoritative policy route before an admission enters account work. */
@FunctionalInterface
public interface AdmissionRoutingPolicyResolver {
  /**
   * Selects the active policy and explicit partition for one validated command.
   *
   * @param command validated admission command
   * @param at instant used for effective-interval selection
   * @return policy identity and partition that must be persisted together
   */
  AdmissionDeliveryRoute resolve(AdmissionCommand command, Instant at);
}
