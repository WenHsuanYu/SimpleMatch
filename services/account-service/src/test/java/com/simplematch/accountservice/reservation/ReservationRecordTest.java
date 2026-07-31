package com.simplematch.accountservice.reservation;

import static org.assertj.core.api.Assertions.assertThat;

import com.simplematch.accountservice.authority.AccountReservation;
import com.simplematch.accountservice.authority.ReservationOutcome;
import com.simplematch.accountservice.authority.ReservationOwnership;
import com.simplematch.contracts.common.v1.ReservationStatus;
import com.simplematch.contracts.common.v1.Side;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ReservationRecordTest {
  @DisplayName("response projection preserves authoritative semantic values and gRPC fields")
  @Test
  void projectsAuthoritativeReservation() {
    final AccountReservation authoritative =
        AccountReservation.accepted(
            new ReservationIdentity(
                new ReservationIdentity.RequestId("request-1"),
                new ReservationIdentity.ReservationId("reservation-1"),
                new ReservationIdentity.OrderId("order-1")),
            new ReservationOwnership("account-1"),
            new ReservationTerms(
                new ReservationTerms.InstrumentSymbol("2330"),
                Side.SIDE_BUY,
                new ReservationTerms.ReservationQuantity(new BigDecimal("10")),
                new ReservationTerms.LimitPrice(new BigDecimal("100"))),
            new BigDecimal("1000"),
            100L);

    final ReservationRecord response = ReservationRecord.from(authoritative);

    assertThat(response.identity()).isEqualTo(authoritative.identity());
    assertThat(response.ownership()).isEqualTo(authoritative.ownership());
    assertThat(response.terms()).isEqualTo(authoritative.terms());
    assertThat(response.state().outcome()).isEqualTo(ReservationOutcome.accepted());
    assertThat(response.state().timing().createdAtUnixMs()).isEqualTo(100L);
    assertThat(response.state().timing().updatedAtUnixMs()).isEqualTo(100L);
    assertThat(response.requestId()).isEqualTo("request-1");
    assertThat(response.reservationId()).isEqualTo("reservation-1");
    assertThat(response.orderId()).isEqualTo("order-1");
    assertThat(response.accountId()).isEqualTo("account-1");
    assertThat(response.symbol()).isEqualTo("2330");
    assertThat(response.side()).isEqualTo(Side.SIDE_BUY);
    assertThat(response.quantity()).isEqualByComparingTo("10");
    assertThat(response.limitPrice()).isEqualByComparingTo("100");
    assertThat(response.reservedNotional()).isEqualByComparingTo("1000");
    assertThat(response.status()).isEqualTo(ReservationStatus.RESERVATION_STATUS_ACCEPTED);
    assertThat(response.reasonCode()).isEmpty();
    assertThat(response.reasonText()).isEmpty();
    assertThat(response.createdAtUnixMs()).isEqualTo(100L);
    assertThat(response.updatedAtUnixMs()).isEqualTo(100L);
  }
}
