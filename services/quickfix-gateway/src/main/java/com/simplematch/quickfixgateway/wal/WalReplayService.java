package com.simplematch.quickfixgateway.wal;

import com.simplematch.contracts.orders.v1.CommandType;
import com.simplematch.contracts.orders.v1.OrderCommand;
import com.simplematch.quickfixgateway.risk.RiskSubmissionClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Re-admits locally durable gateway commands through Risk during owner startup recovery. */
public final class WalReplayService {
  private static final Logger logger = LoggerFactory.getLogger(WalReplayService.class);

  private final WalAppender walAppender;
  private final RiskSubmissionClient riskSubmissionClient;

  /** Creates a replay service for the gateway's WAL and idempotent Risk boundary. */
  public WalReplayService(WalAppender walAppender, RiskSubmissionClient riskSubmissionClient) {
    this.walAppender = walAppender;
    this.riskSubmissionClient = riskSubmissionClient;
  }

  /** Re-admits every durable WAL record and returns the number replayed. */
  public int replayAll() {
    int replayed = 0;
    for (WalRecord walRecord : walAppender.readAll()) {
      submitToRisk(walRecord.toOrderCommand());
      replayed += 1;
    }
    logger.info("replayed {} WAL records from {}", replayed, walAppender.walPath());
    return replayed;
  }

  private void submitToRisk(OrderCommand command) {
    if (command.getCommandType() == CommandType.COMMAND_TYPE_CANCEL) {
      riskSubmissionClient.submitCancel(command);
      return;
    }
    if (command.getCommandType() == CommandType.COMMAND_TYPE_NEW) {
      riskSubmissionClient.submitNewOrder(command);
      return;
    }
    throw new IllegalStateException("unsupported WAL command type: " + command.getCommandType());
  }
}
