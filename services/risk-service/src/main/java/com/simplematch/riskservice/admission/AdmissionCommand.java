package com.simplematch.riskservice.admission;

import java.util.Objects;

/**
 * Transport-independent validated command entering durable risk admission.
 *
 * <p>The command is composed from identity, order facts, FIX business identity, and routing
 * reference. Same-shaped UUID and string fields use different Java types, so the canonical
 * constructor cannot accept a command identifier where an order or account identifier belongs.
 *
 * @param identity the command, order, and account identities
 * @param order the validated instrument, order characteristics, and trading day
 * @param fixIdentity the FIX business identity used for idempotency
 * @param routing the optional market-routing snapshot reference
 */
public record AdmissionCommand(
    AdmissionIdentity identity,
    AdmissionOrder order,
    AdmissionFixIdentity fixIdentity,
    AdmissionRoutingReference routing) {
  /** Requires all four domain components. */
  public AdmissionCommand {
    identity = Objects.requireNonNull(identity, "identity");
    order = Objects.requireNonNull(order, "order");
    fixIdentity = Objects.requireNonNull(fixIdentity, "fixIdentity");
    routing = Objects.requireNonNull(routing, "routing");
  }
}
