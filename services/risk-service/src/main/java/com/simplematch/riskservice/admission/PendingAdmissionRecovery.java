package com.simplematch.riskservice.admission;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Owns scheduled retry orchestration for pending Admission aggregates. */
@Service
public final class PendingAdmissionRecovery {
  private static final Duration RECOVERY_AGE = Duration.ofSeconds(30);
  private static final int RECOVERY_BATCH_SIZE = 100;
  private static final long RECOVERY_INTERVAL_MILLIS = 30_000L;

  private final AdmissionJournalRepository journal;
  private final AccountReservationClient account;
  private final AdmissionLifecycleTransactions lifecycleTransactions;
  private final Clock clock;

  /**
   * Creates the recovery module over durable journal, remote reservation, and local transaction
   * seams.
   *
   * @param journal durable pending-admission source
   * @param account account reservation client used outside local transactions
   * @param lifecycleTransactions local transaction owner for terminal state
   * @param clock source of the recovery cutoff
   */
  public PendingAdmissionRecovery(
      AdmissionJournalRepository journal,
      AccountReservationClient account,
      AdmissionLifecycleTransactions lifecycleTransactions,
      Clock clock) {
    this.journal = Objects.requireNonNull(journal, "journal");
    this.account = Objects.requireNonNull(account, "account");
    this.lifecycleTransactions =
        Objects.requireNonNull(lifecycleTransactions, "lifecycleTransactions");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * Retries one bounded batch of old pending admissions.
   *
   * <p>Remote reservation work happens before the local terminal transaction. A failure for one
   * row is isolated so the row remains eligible for a later pass and does not prevent other rows
   * from progressing.
   *
   * @return the number of rows finalized during this pass
   */
  @Scheduled(fixedDelay = RECOVERY_INTERVAL_MILLIS)
  public int recover() {
    final List<AdmissionJournalEntry> pending =
        journal.findPendingBefore(Instant.now(clock).minus(RECOVERY_AGE), RECOVERY_BATCH_SIZE);
    int recovered = 0;
    for (AdmissionJournalEntry entry : pending) {
      try {
        final AdmissionCommand command = entry.command();
        final ReservationOutcome outcome =
            command.order().isCancellation()
                ? ReservationOutcome.accepted(null)
                : account.reserve(command);
        lifecycleTransactions.finalizeAdmission(
            command.identity().commandId().value(), outcome);
        recovered++;
      } catch (RuntimeException ignored) {
        // Keep the pending row durable and eligible for the next bounded recovery pass.
      }
    }
    return recovered;
  }
}
