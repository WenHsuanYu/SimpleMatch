package com.simplematch.accountservice.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.simplematch.contracts.common.v2.Side;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ReservationCommandModelTest {
  private static final String ACCOUNT_ID = "0194a8f0-7c77-7b38-9e2d-2a5fdd0f7c13";

  @DisplayName("Reserve commands expose identity and terms through domain values")
  @Test
  void composesReserveOperation() {
    final ReserveOperation operation =
        new ReserveOperation(
            new ReservationRequestIdentity(
                new ReservationRequestIdentity.RequestId("00000000-0000-0000-0000-000000000011"),
                new ReservationRequestIdentity.OrderId("00000000-0000-0000-0000-000000000013"),
                new ReservationRequestIdentity.AccountId(ACCOUNT_ID)),
            new ReservationTerms(
                new ReservationTerms.InstrumentSymbol("2330"),
                new ReservationTerms.VenueMic("XTAI"),
                Side.SIDE_BUY,
                new ReservationTerms.ReservationQuantity(1000),
                new ReservationTerms.LimitPrice(new BigDecimal("950.5"))), java.time.LocalDate.of(2026, 7, 28));

    assertThat(operation.requestId()).isEqualTo("00000000-0000-0000-0000-000000000011");
    assertThat(operation.orderId()).isEqualTo("00000000-0000-0000-0000-000000000013");
    assertThat(operation.accountId()).isEqualTo(ACCOUNT_ID);
    assertThat(operation.quantity()).isEqualTo(1000L);
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
    final ReleaseReservationOperation operation =
        new ReleaseReservationOperation(
            new ReservationIdentity(
                new ReservationIdentity.RequestId("00000000-0000-0000-0000-000000000011"),
                new ReservationIdentity.ReservationId("00000000-0000-0000-0000-000000000012"),
                new ReservationIdentity.OrderId("00000000-0000-0000-0000-000000000013")),
            new ReleaseReservationOperation.ReleaseReason("ORDER_CANCELED"));

    assertThat(operation.reservation().reservationId().wireValue()).isEqualTo("00000000-0000-0000-0000-000000000012");
    assertThat(operation.reason().value()).isEqualTo("ORDER_CANCELED");
  }
}
