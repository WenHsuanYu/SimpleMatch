package com.simplematch.accountservice.matching;

import com.simplematch.accountservice.reservation.AccountMatchingExecutionHandler;
import com.simplematch.accountservice.reservation.MatchingAccountEffect;
import com.simplematch.accountservice.store.JdbcFinalMatchingEventAccountInbox;
import java.time.Clock;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Applies translated final Matching Event effects in one Account Authority transaction. */
@Service
public class FinalMatchingEventAccountApplicationService
    implements FinalMatchingEventAccountHandler {
  private static final int TRANSACTION_TIMEOUT_SECONDS = 8;

  private final JdbcFinalMatchingEventAccountInbox inbox;
  private final AccountMatchingExecutionHandler matchingExecutionHandler;
  private final Clock clock;

  /** Creates the final-event transaction with Account-owned application collaborators. */
  public FinalMatchingEventAccountApplicationService(
      JdbcFinalMatchingEventAccountInbox inbox,
      AccountMatchingExecutionHandler matchingExecutionHandler,
      Clock clock) {
    this.inbox = inbox;
    this.matchingExecutionHandler = matchingExecutionHandler;
    this.clock = clock;
  }

  /** Applies exact-event inbox evidence, authority effects, and progress atomically. */
  @Override
  @Transactional(timeout = TRANSACTION_TIMEOUT_SECONDS)
  public FinalMatchingEventAccountOutcome apply(
      FinalMatchingEventAccountCommand command, int kafkaPartition, long kafkaOffset) {
    final FinalMatchingEventAccountCommand finalCommand =
        Objects.requireNonNull(command, "command");
    final long now = clock.millis();
    if (!inbox.claim(finalCommand.eventId(), finalCommand.payloadSha256(), now)) {
      inbox.recordProgress(kafkaPartition, kafkaOffset, now);
      return FinalMatchingEventAccountOutcome.DUPLICATE;
    }
    for (MatchingAccountEffect effect : finalCommand.effects()) {
      matchingExecutionHandler.apply(effect);
    }
    inbox.recordProgress(kafkaPartition, kafkaOffset, now);
    return FinalMatchingEventAccountOutcome.APPLIED;
  }
}
