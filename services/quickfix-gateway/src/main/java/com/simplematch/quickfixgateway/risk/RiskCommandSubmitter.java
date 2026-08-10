package com.simplematch.quickfixgateway.risk;

import com.simplematch.contracts.v2.DomainValidationException;
import com.simplematch.quickfixgateway.wal.WalRecord;
import java.util.Objects;

/** Maps durable Gateway commands to v2 and submits them through the Risk transport seam. */
public final class RiskCommandSubmitter {
  private final RiskCommandMapper commandMapper;
  private final RiskSubmissionClient submissionClient;

  /** Creates the direct durable-WAL to v2 Risk submission boundary. */
  public RiskCommandSubmitter(
      RiskCommandMapper commandMapper, RiskSubmissionClient submissionClient) {
    this.commandMapper = Objects.requireNonNull(commandMapper, "commandMapper");
    this.submissionClient = Objects.requireNonNull(submissionClient, "submissionClient");
  }

  /** Maps and submits one durable new order. */
  public RiskSubmissionResult submitNewOrder(WalRecord record) {
    try {
      final RiskSubmissionResult result =
          submissionClient.submitNewOrder(commandMapper.toNewOrder(record));
      return clientFacing(record, result);
    } catch (DomainValidationException invalid) {
      return invalidCommand(record, invalid);
    }
  }

  /** Maps and submits one durable cancellation. */
  public RiskSubmissionResult submitCancel(WalRecord record) {
    try {
      final RiskSubmissionResult result =
          submissionClient.submitCancel(commandMapper.toCancelOrder(record));
      return clientFacing(record, result);
    } catch (DomainValidationException invalid) {
      return invalidCommand(record, invalid);
    }
  }

  private RiskSubmissionResult clientFacing(WalRecord record, RiskSubmissionResult result) {
    return new RiskSubmissionResult(
        record.orderId(), result.outcome(), result.reasonCode(), result.reasonText());
  }

  private RiskSubmissionResult invalidCommand(WalRecord record, DomainValidationException invalid) {
    return new RiskSubmissionResult(
        record.orderId(),
        RiskSubmissionResult.Outcome.REJECTED,
        "INVALID_COMMAND",
        invalid.getMessage());
  }
}
