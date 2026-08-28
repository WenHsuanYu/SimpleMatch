package com.simplematch.riskservice.admission;

import com.simplematch.riskservice.outbox.OutboxRepository;
import java.util.Objects;
import org.springframework.transaction.support.TransactionTemplate;

/** Persists each complete daily barrier set through Risk's local transaction boundary. */
public final class TradingSessionBarrierService {
  private final MatchingBarrierOutboxFactory barriers;
  private final OutboxRepository outbox;
  private final TransactionTemplate transactionTemplate;

  /**
   * Creates the application service over deterministic barriers and Risk's local outbox boundary.
   */
  public TradingSessionBarrierService(
      MatchingBarrierOutboxFactory barriers,
      OutboxRepository outbox,
      TransactionTemplate transactionTemplate) {
    this.barriers = Objects.requireNonNull(barriers, "barriers");
    this.outbox = Objects.requireNonNull(outbox, "outbox");
    this.transactionTemplate = Objects.requireNonNull(transactionTemplate, "transactionTemplate");
  }

  /** Persists the complete Open Barrier set and returns how many records were newly inserted. */
  public int open(String tradingSessionId) {
    return publish(barriers.open(tradingSessionId));
  }

  /** Persists the complete Close Barrier set and returns how many records were newly inserted. */
  public int close(String tradingSessionId) {
    return publish(barriers.close(tradingSessionId));
  }

  private int publish(java.util.List<com.simplematch.riskservice.outbox.OutboxRecord> records) {
    final Integer inserted =
        transactionTemplate.execute(
            status -> {
              int count = 0;
              for (var record : records) {
                if (outbox.insertIfAbsent(record)) {
                  count++;
                }
              }
              return count;
            });
    return Objects.requireNonNull(inserted, "barrier transaction result");
  }
}
