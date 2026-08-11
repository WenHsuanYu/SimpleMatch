package com.simplematch.riskservice.admission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.simplematch.contracts.common.v2.OrderType;
import com.simplematch.contracts.common.v2.Side;
import com.simplematch.contracts.common.v2.TimeInForce;
import com.simplematch.contracts.matching.runtime.v1.MatchingCommand;
import com.simplematch.marketreference.ArtifactIdentity;
import com.simplematch.riskservice.outbox.OutboxRecord;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Tests final Matching command routing at the Risk outbox boundary. */
class AdmissionOutboxFactoryTest {
  private static final UUID SNAPSHOT_ID = UUID.randomUUID();
  private static final ArtifactIdentity ARTIFACT_IDENTITY =
      new ArtifactIdentity(
          LocalDate.of(2026, 8, 11),
          "7cd06c51691bcde248e606ed1adfaddc4bd10ece582a6803fd2f04155a032943");
  private final AdmissionOutboxFactory factory =
      new AdmissionOutboxFactory(
          "matching.commands",
          Clock.fixed(Instant.ofEpochMilli(300L), ZoneOffset.UTC));

  @DisplayName(
      "accepted new orders use command identity and persist the complete artifact route")
  @Test
  void acceptedNewOrderUsesPersistedArtifactRoute() throws Exception {
    final AdmissionJournalEntry entry =
        accepted(AdmissionDeliveryRoute.assigned(ARTIFACT_IDENTITY, "stable-least-loaded-v1", 7));

    final OutboxRecord record = factory.create(entry).orElseThrow();
    final MatchingCommand payload = MatchingCommand.parseFrom(record.payloadEnvelope().payload());

    assertThat(record.routing().topic()).isEqualTo("matching.commands");
    assertThat(record.routing().messageKey()).isEqualTo(entry.command().identity().commandId().value().toString());
    assertThat(record.routing().kafkaPartitionId()).isEqualTo(7);
    assertThat(payload.getHeader().getPartitionId()).isEqualTo(7);
    assertThat(payload.getHeader().getArtifactIdentity().getTradingDay()).isEqualTo("2026-08-11");
    assertThat(payload.getHeader().getArtifactIdentity().getContentSha256())
        .isEqualTo(ARTIFACT_IDENTITY.contentSha256());
    assertThat(payload.getNewOrder().getOrderType()).isEqualTo(OrderType.ORDER_TYPE_LIMIT);
    assertThat(payload.getNewOrder().getTimeInForce()).isEqualTo(TimeInForce.TIME_IN_FORCE_ROD);
    assertThat(payload.getNewOrder().getSide()).isEqualTo(Side.SIDE_BUY);
  }

  @DisplayName("accepted commands reject a missing persisted artifact identity")
  @Test
  void acceptedCommandRequiresPersistedArtifactIdentity() {
    final AdmissionJournalEntry entry = accepted(AdmissionDeliveryRoute.assigned(7));

    assertThatThrownBy(() -> factory.create(entry))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("matching command requires a persisted artifact identity");
  }

  @DisplayName("rejected admissions do not create a command that could reach Matching")
  @Test
  void rejectedAdmissionDoesNotCreateMatchingCommand() {
    final AdmissionJournalEntry rejected =
        AdmissionJournalEntry.pending(
                command(),
                AdmissionDeliveryRoute.assigned(ARTIFACT_IDENTITY, "stable-least-loaded-v1", 7),
                100L)
            .finalizeWith(ReservationOutcome.rejected("INSUFFICIENT", "cash"), 200L);

    assertThat(factory.create(rejected)).isEmpty();
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
            ARTIFACT_IDENTITY.tradingDay()),
        new AdmissionFixIdentity(
            new AdmissionFixIdentity.SenderCompId("SENDER"),
            new AdmissionFixIdentity.TargetCompId("TARGET"),
            new AdmissionFixIdentity.ClOrdId("CL-1")),
        new AdmissionRoutingReference(
            new AdmissionRoutingReference.RoutingSnapshotId(SNAPSHOT_ID)));
  }
}
