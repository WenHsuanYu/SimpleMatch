package com.simplematch.accountservice.reservation;

import com.simplematch.contracts.common.v1.Side;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReservationCommandModelTest {
    @DisplayName("Reserve commands expose identity and terms through domain values")
    @Test
    void composesReserveOperation() {
        final ReserveOperation operation = new ReserveOperation(
                new ReservationRequestIdentity(
                        new ReservationRequestIdentity.RequestId("request-1"),
                        new ReservationRequestIdentity.OrderId("order-1"),
                        new ReservationRequestIdentity.AccountId("account-1")),
                new ReservationTerms(
                        new ReservationTerms.InstrumentSymbol("2330"),
                        Side.SIDE_BUY,
                        new ReservationTerms.ReservationQuantity(new BigDecimal("1000")),
                        new ReservationTerms.LimitPrice(new BigDecimal("950.5"))));

        assertThat(operation.requestId()).isEqualTo("request-1");
        assertThat(operation.orderId()).isEqualTo("order-1");
        assertThat(operation.accountId()).isEqualTo("account-1");
        assertThat(operation.quantity()).isEqualByComparingTo("1000");
        assertThat(operation.limitPrice()).isEqualByComparingTo("950.5");
    }

    @DisplayName("Reservation terms reject a nonpositive limit price before transaction work")
    @Test
    void rejectsInvalidLimitPrice() {
        assertThatThrownBy(() -> new ReservationTerms.LimitPrice(BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("limit_price must be positive");
    }

    @DisplayName("Release commands carry one coherent reservation identity")
    @Test
    void composesReleaseOperation() {
        final ReleaseReservationOperation operation = new ReleaseReservationOperation(
                new ReservationIdentity(
                        new ReservationIdentity.RequestId("request-1"),
                        new ReservationIdentity.ReservationId("reservation-1"),
                        new ReservationIdentity.OrderId("order-1")),
                new ReleaseReservationOperation.ReleaseReason("ORDER_CANCELED"));

        assertThat(operation.reservation().reservationId().value()).isEqualTo("reservation-1");
        assertThat(operation.reason().value()).isEqualTo("ORDER_CANCELED");
    }
}
