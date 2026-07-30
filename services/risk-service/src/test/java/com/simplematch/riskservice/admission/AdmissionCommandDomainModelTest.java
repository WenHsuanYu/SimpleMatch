package com.simplematch.riskservice.admission;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdmissionCommandDomainModelTest {
    @DisplayName("Admission commands compose identity, order, FIX identity, and routing")
    @Test
    void composesAdmissionCommand() {
        final UUID commandId = UUID.randomUUID();
        final UUID orderId = UUID.randomUUID();
        final UUID accountId = UUID.randomUUID();
        final UUID snapshotId = UUID.randomUUID();
        final AdmissionCommand command = new AdmissionCommand(
                new AdmissionIdentity(
                        new AdmissionIdentity.CommandId(commandId),
                        new AdmissionIdentity.OrderId(orderId),
                        new AdmissionIdentity.AccountId(accountId)),
                new AdmissionOrder(
                        new AdmissionOrder.Instrument(
                                new AdmissionOrder.Symbol("2330"),
                                new AdmissionOrder.VenueMic("XTAI")),
                        new AdmissionOrder.Characteristics(
                                new AdmissionOrder.SideCode("SIDE_BUY"),
                                new AdmissionOrder.Quantity(1000),
                                new AdmissionOrder.LimitPriceUnits(9_505_000L),
                                new AdmissionOrder.OrderTypeCode("ORDER_TYPE_LIMIT"),
                                new AdmissionOrder.TimeInForceCode("TIME_IN_FORCE_ROD")),
                        LocalDate.of(2026, 7, 28)),
                new AdmissionFixIdentity(
                        new AdmissionFixIdentity.SenderCompId("CLIENT"),
                        new AdmissionFixIdentity.TargetCompId("SIMPLEMATCH"),
                        new AdmissionFixIdentity.ClOrdId("C1")),
                new AdmissionRoutingReference(
                        new AdmissionRoutingReference.RoutingSnapshotId(snapshotId)));

        assertThat(command.identity().commandId().value()).isEqualTo(commandId);
        assertThat(command.identity().orderId().value()).isEqualTo(orderId);
        assertThat(command.identity().accountId().value()).isEqualTo(accountId);
        assertThat(command.order().instrument().symbol().value()).isEqualTo("2330");
        assertThat(command.routing().snapshotId().value()).isEqualTo(snapshotId);
    }

    @DisplayName("Admission failures render a stable code and detail")
    @Test
    void formatsAdmissionFailure() {
        final AdmissionValidationException exception = new AdmissionValidationException(
                AdmissionFailure.invalidInstrument("symbol is required"));

        assertThat(exception.getMessage()).isEqualTo("INVALID_INSTRUMENT: symbol is required");
        assertThat(exception.reasonCode()).isEqualTo("INVALID_INSTRUMENT");
    }

    @DisplayName("Admission quantities reject invalid state before persistence")
    @Test
    void rejectsInvalidQuantity() {
        assertThatThrownBy(() -> new AdmissionOrder.Quantity(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("quantity must be positive");
    }
}
