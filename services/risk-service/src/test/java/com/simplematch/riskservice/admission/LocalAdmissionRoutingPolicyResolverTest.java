package com.simplematch.riskservice.admission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.simplematch.riskservice.routing.RoutingInstrument;
import com.simplematch.riskservice.routing.RoutingPolicyAssignment;
import com.simplematch.riskservice.routing.RoutingPolicyPartitionTopology;
import com.simplematch.riskservice.routing.RoutingPolicyProjection;
import com.simplematch.riskservice.routing.RoutingPolicyProjectionIdentity;
import com.simplematch.riskservice.routing.RoutingPolicyProjectionInterval;
import com.simplematch.riskservice.routing.RoutingPolicyProjectionRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Tests the fail-closed selection boundary between Admission and the local policy projection. */
class LocalAdmissionRoutingPolicyResolverTest {
  private static final UUID POLICY_ID = UUID.fromString("0194a8f0-7c77-7b38-9e2d-2a5fdd0f7c01");
  private static final LocalDate TRADING_DAY = LocalDate.of(2026, 7, 28);
  private static final Instant NOW = Instant.parse("2026-07-28T01:00:00Z");

  @Test
  @DisplayName("selects the active policy identity and partition as one route")
  void selectsPolicyRoute() {
    final LocalAdmissionRoutingPolicyResolver resolver =
        new LocalAdmissionRoutingPolicyResolver(new TestRepository(Optional.of(projection())));

    assertThat(resolver.resolve(command(), NOW))
        .isEqualTo(AdmissionDeliveryRoute.assigned(POLICY_ID, 7));
  }

  @Test
  @DisplayName("rejects an absent active policy before account work")
  void rejectsMissingPolicy() {
    final LocalAdmissionRoutingPolicyResolver resolver =
        new LocalAdmissionRoutingPolicyResolver(new TestRepository(Optional.empty()));

    assertThatThrownBy(() -> resolver.resolve(command(), NOW))
        .isInstanceOf(AdmissionValidationException.class)
        .extracting(error -> ((AdmissionValidationException) error).reasonCode())
        .isEqualTo("ROUTING_POLICY_UNAVAILABLE");
  }

  @Test
  @DisplayName("rejects an instrument not assigned by the active policy")
  void rejectsUnknownInstrument() {
    final LocalAdmissionRoutingPolicyResolver resolver =
        new LocalAdmissionRoutingPolicyResolver(new TestRepository(Optional.of(projection())));
    final AdmissionCommand unknown = command("9999");

    assertThatThrownBy(() -> resolver.resolve(unknown, NOW))
        .isInstanceOf(AdmissionValidationException.class)
        .extracting(error -> ((AdmissionValidationException) error).reasonCode())
        .isEqualTo("ROUTING_INSTRUMENT_NOT_ASSIGNED");
  }

  private static RoutingPolicyProjection projection() {
    return new RoutingPolicyProjection(
        new RoutingPolicyProjectionIdentity(POLICY_ID, UUID.randomUUID(), TRADING_DAY),
        new RoutingPolicyProjectionInterval(
            Instant.parse("2026-07-28T00:00:00Z"), Instant.parse("2026-07-28T02:00:00Z")),
        new RoutingPolicyPartitionTopology(16),
        List.of(new RoutingPolicyAssignment(new RoutingInstrument("2330", "XTAI"), 7)));
  }

  private static AdmissionCommand command() {
    return command("2330");
  }

  private static AdmissionCommand command(String symbol) {
    return new AdmissionCommand(
        new AdmissionIdentity(
            new AdmissionIdentity.CommandId(UUID.randomUUID()),
            new AdmissionIdentity.OrderId(UUID.randomUUID()),
            new AdmissionIdentity.AccountId(UUID.randomUUID())),
        new AdmissionOrder(
            new AdmissionOrder.Instrument(
                new AdmissionOrder.Symbol(symbol), new AdmissionOrder.VenueMic("XTAI")),
            new AdmissionOrder.Characteristics(
                new AdmissionOrder.SideCode("SIDE_BUY"),
                new AdmissionOrder.Quantity(10),
                new AdmissionOrder.LimitPriceUnits(1_000_000L),
                new AdmissionOrder.OrderTypeCode("LIMIT"),
                new AdmissionOrder.TimeInForceCode("ROD")),
            TRADING_DAY),
        new AdmissionFixIdentity(
            new AdmissionFixIdentity.SenderCompId("SENDER"),
            new AdmissionFixIdentity.TargetCompId("TARGET"),
            new AdmissionFixIdentity.ClOrdId("CL-1")),
        new AdmissionRoutingReference(new AdmissionRoutingReference.RoutingSnapshotId(null)));
  }

  private static final class TestRepository implements RoutingPolicyProjectionRepository {
    private final Optional<RoutingPolicyProjection> applicable;

    private TestRepository(Optional<RoutingPolicyProjection> applicable) {
      this.applicable = applicable;
    }

    @Override
    public Optional<RoutingPolicyProjection> findById(UUID routingPolicyId) {
      return applicable;
    }

    @Override
    public Optional<RoutingPolicyProjection> findApplicable(LocalDate tradingDay, Instant at) {
      return applicable;
    }

    @Override
    public Optional<RoutingPolicyProjection> findLatestActive() {
      return applicable;
    }

    @Override
    public void insertStaged(RoutingPolicyProjection projection, Instant receivedAt) {}

    @Override
    public void activate(UUID routingPolicyId) {}
  }
}
