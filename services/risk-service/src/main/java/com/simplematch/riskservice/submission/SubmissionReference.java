package com.simplematch.riskservice.submission;

import java.util.Objects;

/**
 * Domain reference for one risk submission and the order it attempts to change.
 *
 * <p>Command and order identifiers use different Java types, preventing the two same-shaped wire
 * values from being exchanged at construction time. Blank identifiers remain representable so a
 * malformed ingress request can still be persisted as a stable rejection outcome.
 *
 * @param requestId the idempotent submission request identifier
 * @param orderId the affected order identifier
 * @param commandType the normalized order command type
 */
public record SubmissionReference(
    SubmissionCommand.CommandId requestId,
    SubmissionCommand.OrderId orderId,
    CommandType commandType) {
  /** Requires explicit typed identifiers and a command type. */
  public SubmissionReference {
    requestId = Objects.requireNonNull(requestId, "requestId");
    orderId = Objects.requireNonNull(orderId, "orderId");
    commandType = Objects.requireNonNull(commandType, "commandType");
  }
}
