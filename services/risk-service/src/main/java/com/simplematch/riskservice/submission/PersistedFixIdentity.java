package com.simplematch.riskservice.submission;

import java.util.Objects;

/**
 * Storage-safe representation of FIX identifiers used by durable deduplication.
 *
 * @param clOrdId the bounded or deterministic-surrogate client order identifier
 * @param origClOrdId the bounded or deterministic-surrogate original client order identifier
 * @param surrogated whether any business-key component required a deterministic surrogate
 */
public record PersistedFixIdentity(
    SubmissionCommand.ClOrdId clOrdId,
    SubmissionCommand.OrigClOrdId origClOrdId,
    boolean surrogated) {
  /** Requires explicit typed storage values. */
  public PersistedFixIdentity {
    Objects.requireNonNull(clOrdId, "clOrdId");
    Objects.requireNonNull(origClOrdId, "origClOrdId");
  }
}
