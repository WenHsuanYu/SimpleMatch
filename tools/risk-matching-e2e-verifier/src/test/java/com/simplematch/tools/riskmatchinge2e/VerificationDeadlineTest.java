package com.simplematch.tools.riskmatchinge2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** Focused tests for the verifier-wide monotonic deadline. */
class VerificationDeadlineTest {
  @Test
  void remainingBudgetDecreasesFromTheOriginalDeadline() {
    final AtomicLong ticker = new AtomicLong();
    final VerificationDeadline deadline =
        new VerificationDeadline(Duration.ofSeconds(90), ticker::get);

    ticker.addAndGet(Duration.ofSeconds(17).toNanos());

    assertEquals(Duration.ofSeconds(73), deadline.remaining());
  }

  @Test
  void requireRemainingFailsAfterTheSharedBudgetExpires() {
    final AtomicLong ticker = new AtomicLong();
    final VerificationDeadline deadline =
        new VerificationDeadline(Duration.ofSeconds(2), ticker::get);

    ticker.addAndGet(Duration.ofSeconds(2).toNanos());

    final VerificationFailure failure =
        assertThrows(
            VerificationFailure.class,
            () ->
                deadline.requireRemaining(
                    VerificationFailure.Stage.ADMISSION_RECONCILIATION,
                    VerificationFailure.Code.ADMISSION_REMAINED_PENDING,
                    "admission remained pending until the verifier deadline"));

    assertEquals(
        VerificationFailure.Code.ADMISSION_REMAINED_PENDING,
        failure.code());
  }

  @Test
  void laterStagesReceiveOnlyTheUnspentBudget() {
    final AtomicLong ticker = new AtomicLong();
    final VerificationDeadline deadline =
        new VerificationDeadline(Duration.ofSeconds(90), ticker::get);

    ticker.addAndGet(Duration.ofSeconds(47).toNanos());

    assertEquals(
        Duration.ofSeconds(43),
        deadline.requireRemaining(
            VerificationFailure.Stage.KAFKA_OBSERVATION,
            VerificationFailure.Code.KAFKA_COMMAND_NOT_OBSERVED,
            "matching command was not observed"));
  }
}
