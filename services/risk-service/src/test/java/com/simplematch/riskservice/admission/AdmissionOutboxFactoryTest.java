package com.simplematch.riskservice.admission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.simplematch.contracts.orders.v2.OrderAdmissionAccepted;
import com.simplematch.riskservice.outbox.OutboxRecord;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Tests v2 admission event routing at the outbox boundary. */
class AdmissionOutboxFactoryTest {
  private static final UUID SNAPSHOT_ID = UUID.randomUUID();
  private final AdmissionOutboxFactory factory =
      new AdmissionOutboxFactory(
          "orders.validated",
          Clock.fixed(Instant.ofEpochMilli(300L), ZoneOffset.UTC),
          symbol -> 7);

  @DisplayName("accepted events use the symbol key and agree on explicit partition metadata")
  @Test
  void acceptedEventUsesPersistedSymbolRoute() throws Exception {
    final AdmissionJournalEntry entry = accepted(AdmissionDeliveryRoute.assigned(7));

    final OutboxRecord record = factory.create(entry);
    final OrderAdmissionAccepted payload =
        OrderAdmissionAccepted.parseFrom(record.payloadEnvelope().payload());

    assertThat(record.routing().topic()).isEqualTo("orders.validated");
    assertThat(record.routing().messageKey()).isEqualTo("2330");
    assertThat(record.routing().kafkaPartitionId()).isEqualTo(7);
    assertThat(payload.getRoutingPartition()).isEqualTo(7);
    assertThat(payload.getRoutingSnapshotId()).isEqualTo(SNAPSHOT_ID.toString());
  }

  @DisplayName("accepted events reject a missing persisted partition instead of encoding zero")
  @Test
  void acceptedEventRequiresPersistedPartition() {
    final AdmissionJournalEntry entry = accepted(AdmissionDeliveryRoute.unassigned());

    assertThatThrownBy(() -> factory.create(entry))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("accepted admission requires a persisted routing partition");
  }

  private AdmissionJournalEntry accepted(AdmissionDeliveryRoute route) {
    return AdmissionJournalEntry.pending(command(), route, 100L)
        .finalizeWith(ReservationOutcome.accepted(UUID.randomUUID()), 200L);
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
            new AdmissionRoutingReference.RoutingSnapshotId(SNAPSHOT_ID)));
  }
}
