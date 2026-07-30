package com.simplematch.accountservice.reservation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApplyFillOperationTest {
    @DisplayName("Fill commands group reservation identity and execution facts")
    @Test
    void groupsReservationAndExecutionValues() {
        final ReservationIdentity identity = new ReservationIdentity(
                new ReservationIdentity.RequestId("request-1"),
                new ReservationIdentity.ReservationId("reservation-1"),
                new ReservationIdentity.OrderId("order-1"));
        final ExecutionFill fill = new ExecutionFill(
                new ExecutionFill.ExecutionId("execution-1"),
                new ExecutionFill.AggregateSequence(7L),
                new ExecutionFill.FillQuantity(new BigDecimal("10")),
                new ExecutionFill.FillPrice(new BigDecimal("99")));

        final ApplyFillOperation operation = new ApplyFillOperation(identity, fill);

        assertThat(operation.reservation()).isEqualTo(identity);
        assertThat(operation.fill()).isEqualTo(fill);
        assertThat(operation.fill().notional()).isEqualByComparingTo("990");
    }

    @DisplayName("Fill commands reject invalid execution quantities before transaction work")
    @Test
    void rejectsInvalidExecutionQuantity() {
        assertThatThrownBy(() -> new ExecutionFill.FillQuantity(BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("fill_quantity must be positive");
    }
}
