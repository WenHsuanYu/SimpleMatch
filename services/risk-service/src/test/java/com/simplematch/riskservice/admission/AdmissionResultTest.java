package com.simplematch.riskservice.admission;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Tests the journal-to-result projection at the Risk Admission domain seam. */
class AdmissionResultTest {
  @Test
  @DisplayName("projection keeps semantic result values without journal revision")
  void projectsIdentityDecisionProvenanceAndRouteWithoutJournalRevision() {
    final AdmissionCommand command = command();
    final AdmissionDeliveryRoute route = AdmissionDeliveryRoute.assigned(3);
    final AdmissionJournalEntry entry =
        AdmissionJournalEntry.pending(command, route, 100L)
            .finalizeWith(ReservationOutcome.accepted(UUID.randomUUID()), 200L);

    final AdmissionResult result = AdmissionResult.from(entry);

    assertThat(result.identity()).isEqualTo(command.identity());
    assertThat(result.decision()).isEqualTo(entry.lifecycle().decision());
    assertThat(result.routing()).isEqualTo(command.routing());
    assertThat(result.route()).isEqualTo(route);
  }

  private AdmissionCommand command() {
    return new AdmissionCommand(
        new AdmissionIdentity(
            new AdmissionIdentity.CommandId(UUID.randomUUID()),
            new AdmissionIdentity.OrderId(UUID.randomUUID()),
            new AdmissionIdentity.AccountId(UUID.randomUUID())),
        new AdmissionOrder(
            new AdmissionOrder.Instrument(
                new AdmissionOrder.Symbol("2330"), new AdmissionOrder.VenueMic("XTAI")),
            new AdmissionOrder.Characteristics(
                new AdmissionOrder.SideCode("SIDE_BUY"),
                new AdmissionOrder.Quantity(10L),
                new AdmissionOrder.LimitPriceUnits(1_000_000L),
                new AdmissionOrder.OrderTypeCode("LIMIT"),
                new AdmissionOrder.TimeInForceCode("ROD")),
            LocalDate.of(2026, 7, 28)),
        new AdmissionFixIdentity(
            new AdmissionFixIdentity.SenderCompId("SENDER"),
            new AdmissionFixIdentity.TargetCompId("TARGET"),
            new AdmissionFixIdentity.ClOrdId("CL-1")),
        new AdmissionRoutingReference(
            new AdmissionRoutingReference.RoutingSnapshotId(UUID.randomUUID())));
  }
}
