package com.simplematch.riskservice.admission;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Tests the Risk Admission aggregate lifecycle without persistence or transport adapters. */
class AdmissionJournalEntryTest {
  private static final UUID COMMAND_ID = UUID.randomUUID();
  private static final UUID ORDER_ID = UUID.randomUUID();
  private static final UUID ACCOUNT_ID = UUID.randomUUID();
  private static final UUID SNAPSHOT_ID = UUID.randomUUID();

  @DisplayName("accepted outcome transitions pending admission and records reservation identity")
  @Test
  void acceptedOutcomeTransitionsPendingAdmission() {
    final AdmissionJournalEntry transitioned =
        pending().finalizeWith(ReservationOutcome.accepted(UUID.randomUUID()), 200L);

    assertThat(transitioned.state()).isEqualTo(AdmissionState.ACCEPTED);
    assertThat(transitioned.reservationId()).isNotNull();
    assertThat(transitioned.version()).isEqualTo(1L);
    assertThat(transitioned.updatedAtUnixMs()).isEqualTo(200L);
  }

  @DisplayName("rejected outcome transitions pending admission with stable reason")
  @Test
  void rejectedOutcomeTransitionsPendingAdmission() {
    final AdmissionJournalEntry transitioned =
        pending().finalizeWith(ReservationOutcome.rejected("INSUFFICIENT", "cash"), 200L);

    assertThat(transitioned.state()).isEqualTo(AdmissionState.REJECTED);
    assertThat(transitioned.reasonCode()).isEqualTo("INSUFFICIENT");
    assertThat(transitioned.reasonDetail()).isEqualTo("cash");
    assertThat(transitioned.reservationId()).isNull();
  }

  @DisplayName("terminal admission ignores a replayed different outcome")
  @Test
  void terminalAdmissionIsIdempotent() {
    final AdmissionJournalEntry accepted =
        pending().finalizeWith(ReservationOutcome.accepted(UUID.randomUUID()), 200L);

    assertThat(accepted.finalizeWith(ReservationOutcome.rejected("LATE", "ignored"), 300L))
        .isSameAs(accepted);
  }

  private AdmissionJournalEntry pending() {
    return new AdmissionJournalEntry(
        COMMAND_ID,
        ORDER_ID,
        ACCOUNT_ID,
        "2330",
        "XTAI",
        "SIDE_BUY",
        10L,
        1_000_000L,
        "LIMIT",
        "ROD",
        LocalDate.of(2026, 7, 28),
        "SENDER",
        "TARGET",
        "CL-1",
        SNAPSHOT_ID,
        null,
        AdmissionState.PENDING,
        null,
        "",
        "",
        0L,
        100L,
        100L);
  }
}
