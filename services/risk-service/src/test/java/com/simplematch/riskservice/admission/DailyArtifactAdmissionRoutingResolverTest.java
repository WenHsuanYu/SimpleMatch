package com.simplematch.riskservice.admission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simplematch.marketreference.MarketReferenceArtifactStartupValidator;
import com.simplematch.marketreference.VerifiedMarketReferenceArtifact;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Verifies that Risk resolves each admission only from its one verified daily artifact. */
class DailyArtifactAdmissionRoutingResolverTest {
  private static final LocalDate TRADING_DAY = LocalDate.of(2026, 8, 11);

  @DisplayName("persists the artifact identity and declared route for an eligible instrument")
  @Test
  void resolvesArtifactRoute() throws IOException {
    final VerifiedMarketReferenceArtifact artifact = artifact();
    final DailyArtifactAdmissionRoutingResolver resolver =
        new DailyArtifactAdmissionRoutingResolver(artifact);

    assertThat(resolver.resolve(command("2330", TRADING_DAY), Instant.parse("2026-08-11T01:00:00Z")))
        .isEqualTo(
            AdmissionDeliveryRoute.assigned(
                artifact.identity(), "stable-least-loaded-v1", 0));
  }

  @DisplayName("fails closed when an admission carries a different trading day")
  @Test
  void rejectsDifferentTradingDay() throws IOException {
    final DailyArtifactAdmissionRoutingResolver resolver =
        new DailyArtifactAdmissionRoutingResolver(artifact());

    assertThatThrownBy(
            () ->
                resolver.resolve(
                    command("2330", TRADING_DAY.plusDays(1)),
                    Instant.parse("2026-08-11T01:00:00Z")))
        .isInstanceOf(AdmissionValidationException.class)
        .extracting(error -> ((AdmissionValidationException) error).reasonCode())
        .isEqualTo("ROUTING_POLICY_UNAVAILABLE");
  }

  @DisplayName("fails closed rather than inventing a route for an unknown instrument")
  @Test
  void rejectsUnknownInstrument() throws IOException {
    final DailyArtifactAdmissionRoutingResolver resolver =
        new DailyArtifactAdmissionRoutingResolver(artifact());

    assertThatThrownBy(
            () ->
                resolver.resolve(
                    command("9999", TRADING_DAY), Instant.parse("2026-08-11T01:00:00Z")))
        .isInstanceOf(AdmissionValidationException.class)
        .extracting(error -> ((AdmissionValidationException) error).reasonCode())
        .isEqualTo("ROUTING_INSTRUMENT_NOT_ASSIGNED");
  }

  private static VerifiedMarketReferenceArtifact artifact() throws IOException {
    final MarketReferenceArtifactStartupValidator validator =
        new MarketReferenceArtifactStartupValidator(new ObjectMapper());
    return validator.validate(
        resource("/market-reference/market_reference.json"),
        new String(resource("/market-reference/market_reference.sha256"), StandardCharsets.US_ASCII)
            .trim(),
        TRADING_DAY);
  }

  private static byte[] resource(String path) throws IOException {
    return DailyArtifactAdmissionRoutingResolverTest.class.getResourceAsStream(path).readAllBytes();
  }

  private static AdmissionCommand command(String symbol, LocalDate tradingDay) {
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
                new AdmissionOrder.Quantity(1_000),
                new AdmissionOrder.LimitPriceUnits(1_000_000L),
                new AdmissionOrder.OrderTypeCode("ORDER_TYPE_LIMIT"),
                new AdmissionOrder.TimeInForceCode("TIME_IN_FORCE_ROD")),
            tradingDay),
        new AdmissionFixIdentity(
            new AdmissionFixIdentity.SenderCompId("SENDER"),
            new AdmissionFixIdentity.TargetCompId("TARGET"),
            new AdmissionFixIdentity.ClOrdId("CL-1")),
        new AdmissionRoutingReference(new AdmissionRoutingReference.RoutingSnapshotId(null)));
  }
}
