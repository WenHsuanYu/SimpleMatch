package com.simplematch.riskservice.admission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

  @DisplayName("pending admission owns command, delivery route, and pending lifecycle")
  @Test
  void pendingAdmissionUsesSemanticStateValues() {
    final AdmissionCommand command = command("LIMIT");
    final AdmissionJournalEntry pending =
        AdmissionJournalEntry.pending(command, AdmissionDeliveryRoute.unassigned(), 100L);

    assertThat(pending.command()).isEqualTo(command);
    assertThat(pending.route()).isEqualTo(AdmissionDeliveryRoute.unassigned());
    assertThat(pending.lifecycle().decision()).isInstanceOf(AdmissionDecision.Pending.class);
    assertThat(pending.lifecycle().version()).isZero();
  }

  @DisplayName("accepted new outcome records a reservation identity")
  @Test
  void acceptedNewOutcomeTransitionsPendingAdmission() {
    final UUID reservationId = UUID.randomUUID();
    final AdmissionJournalEntry transitioned =
        pending("LIMIT")
            .finalizeWith(ReservationOutcome.accepted(reservationId), 200L);

    assertThat(transitioned.lifecycle().decision())
        .isInstanceOfSatisfying(
            AdmissionDecision.AcceptedNew.class,
            accepted -> assertThat(accepted.reservationId()).isEqualTo(reservationId));
    assertThat(transitioned.lifecycle().state()).isEqualTo(AdmissionState.ACCEPTED);
    assertThat(transitioned.lifecycle().version()).isEqualTo(1L);
    assertThat(transitioned.lifecycle().updatedAtUnixMs()).isEqualTo(200L);
  }

  @DisplayName("accepted cancel outcome has no reservation identity")
  @Test
  void acceptedCancelOutcomeUsesExplicitDecision() {
    final AdmissionJournalEntry transitioned =
        pending("CANCEL").finalizeWith(ReservationOutcome.accepted(null), 200L);

    assertThat(transitioned.lifecycle().decision())
        .isEqualTo(new AdmissionDecision.AcceptedCancel());
    assertThat(transitioned.lifecycle().state()).isEqualTo(AdmissionState.ACCEPTED);
  }

  @DisplayName("rejected outcome requires and preserves a stable failure")
  @Test
  void rejectedOutcomeTransitionsPendingAdmission() {
    final AdmissionJournalEntry transitioned =
        pending("LIMIT")
            .finalizeWith(ReservationOutcome.rejected("INSUFFICIENT", "cash"), 200L);

    assertThat(transitioned.lifecycle().decision())
        .isInstanceOfSatisfying(
            AdmissionDecision.Rejected.class,
            rejected -> {
              assertThat(rejected.failure().reasonCode().value()).isEqualTo("INSUFFICIENT");
              assertThat(rejected.failure().detail().value()).isEqualTo("cash");
            });
    assertThat(transitioned.lifecycle().state()).isEqualTo(AdmissionState.REJECTED);
  }

  @DisplayName("new-order acceptance cannot omit its reservation identity")
  @Test
  void acceptedNewOutcomeRequiresReservation() {
    assertThatThrownBy(() -> pending("LIMIT").finalizeWith(ReservationOutcome.accepted(null), 200L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("accepted new admission requires a reservation");
  }

  @DisplayName("terminal admission ignores a replayed different outcome")
  @Test
  void terminalAdmissionIsIdempotent() {
    final AdmissionJournalEntry accepted =
        pending("LIMIT").finalizeWith(ReservationOutcome.accepted(UUID.randomUUID()), 200L);

    assertThat(accepted.finalizeWith(ReservationOutcome.rejected("LATE", "ignored"), 300L))
        .isSameAs(accepted);
  }

  private AdmissionJournalEntry pending(String orderType) {
    return AdmissionJournalEntry.pending(
        command(orderType), AdmissionDeliveryRoute.unassigned(), 100L);
  }

  private AdmissionCommand command(String orderType) {
    return new AdmissionCommand(
        new AdmissionIdentity(
            new AdmissionIdentity.CommandId(COMMAND_ID),
            new AdmissionIdentity.OrderId(ORDER_ID),
            new AdmissionIdentity.AccountId(ACCOUNT_ID)),
        new AdmissionOrder(
            new AdmissionOrder.Instrument(
                new AdmissionOrder.Symbol("2330"), new AdmissionOrder.VenueMic("XTAI")),
            new AdmissionOrder.Characteristics(
                new AdmissionOrder.SideCode("SIDE_BUY"),
                new AdmissionOrder.Quantity(10L),
                new AdmissionOrder.LimitPriceUnits(orderType.equals("CANCEL") ? null : 1_000_000L),
                new AdmissionOrder.OrderTypeCode(orderType),
                new AdmissionOrder.TimeInForceCode(orderType.equals("CANCEL") ? "CANCEL" : "ROD")),
            LocalDate.of(2026, 7, 28)),
        new AdmissionFixIdentity(
            new AdmissionFixIdentity.SenderCompId("SENDER"),
            new AdmissionFixIdentity.TargetCompId("TARGET"),
            new AdmissionFixIdentity.ClOrdId("CL-1")),
        new AdmissionRoutingReference(
            new AdmissionRoutingReference.RoutingSnapshotId(SNAPSHOT_ID)));
  }
}
