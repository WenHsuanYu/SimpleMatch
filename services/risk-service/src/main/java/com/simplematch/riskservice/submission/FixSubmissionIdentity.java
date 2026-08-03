package com.simplematch.riskservice.submission;

import java.time.LocalDate;
import java.util.Objects;

/**
 * FIX-facing business identity observed at the risk-service boundary.
 *
 * <p>Sender, target, current client order, and original client order identifiers are distinct value
 * types. The compiler therefore rejects positional exchanges between FIX fields that are all
 * represented as strings on the wire.
 *
 * @param senderCompId the persisted-safe FIX SenderCompID
 * @param targetCompId the persisted-safe FIX TargetCompID
 * @param tradingDay the business trading day
 * @param clOrdId the raw client order identifier echoed to the caller
 * @param origClOrdId the raw original client order identifier for cancel flows
 */
public record FixSubmissionIdentity(
    SubmissionCommand.SenderCompId senderCompId,
    SubmissionCommand.TargetCompId targetCompId,
    LocalDate tradingDay,
    SubmissionCommand.ClOrdId clOrdId,
    SubmissionCommand.OrigClOrdId origClOrdId) {
  /** Requires a complete typed FIX identity. */
  public FixSubmissionIdentity {
    Objects.requireNonNull(senderCompId, "senderCompId");
    Objects.requireNonNull(targetCompId, "targetCompId");
    Objects.requireNonNull(tradingDay, "tradingDay");
    Objects.requireNonNull(clOrdId, "clOrdId");
    Objects.requireNonNull(origClOrdId, "origClOrdId");
  }
}
