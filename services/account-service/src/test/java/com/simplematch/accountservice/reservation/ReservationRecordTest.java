package com.simplematch.accountservice.reservation;

import static org.assertj.core.api.Assertions.assertThat;

import com.simplematch.accountservice.authority.AccountId;
import com.simplematch.accountservice.authority.AccountReservation;
import com.simplematch.accountservice.authority.ReservationOutcome;
import com.simplematch.accountservice.authority.ReservationOwnership;
import com.simplematch.accountservice.authority.ReservationStatus;
import com.simplematch.contracts.common.v2.Side;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ReservationRecordTest {
  private static final String ACCOUNT_ID = "0194a8f0-7c77-7b38-9e2d-2a5fdd0f7c13";

  @DisplayName("response projection preserves authoritative semantic values and gRPC fields")
  @Test
  void projectsAuthoritativeReservation() {
    final AccountReservation authoritative =
        AccountReservation.accepted(
            new ReservationIdentity(
                new ReservationIdentity.RequestId("00000000-0000-0000-0000-000000000011"),
                new ReservationIdentity.ReservationId("00000000-0000-0000-0000-000000000012"),
                new ReservationIdentity.OrderId("00000000-0000-0000-0000-000000000013")),
            new ReservationOwnership(AccountId.parse(ACCOUNT_ID)),
            new ReservationTerms(
                new ReservationTerms.InstrumentSymbol("2330"),
                new ReservationTerms.VenueMic("XTAI"),
                Side.SIDE_BUY,
                new ReservationTerms.ReservationQuantity(10),
                new ReservationTerms.LimitPrice(new BigDecimal("100"))),
            LocalDate.of(2026, 7, 28),
            new BigDecimal("1000"),
            100L);

    final ReservationRecord response = ReservationRecord.from(authoritative);

    assertThat(response.identity()).isEqualTo(authoritative.identity());
    assertThat(response.ownership()).isEqualTo(authoritative.ownership());
    assertThat(response.terms()).isEqualTo(authoritative.terms());
    assertThat(response.state().outcome()).isEqualTo(ReservationOutcome.accepted());
    assertThat(response.state().timing().createdAtUnixMs()).isEqualTo(100L);
    assertThat(response.state().timing().updatedAtUnixMs()).isEqualTo(100L);
    assertThat(response.requestId()).isEqualTo("00000000-0000-0000-0000-000000000011");
    assertThat(response.reservationId()).isEqualTo("00000000-0000-0000-0000-000000000012");
    assertThat(response.orderId()).isEqualTo("00000000-0000-0000-0000-000000000013");
    assertThat(response.accountId()).isEqualTo(ACCOUNT_ID);
    assertThat(response.symbol()).isEqualTo("2330");
    assertThat(response.side()).isEqualTo(Side.SIDE_BUY);
    assertThat(response.quantity()).isEqualTo(10L);
    assertThat(response.limitPrice()).isEqualByComparingTo("100");
    assertThat(response.reservedNotional()).isEqualByComparingTo("1000");
    assertThat(response.status()).isEqualTo(ReservationStatus.RESERVATION_STATUS_ACCEPTED);
    assertThat(response.reasonCode()).isEmpty();
    assertThat(response.reasonText()).isEmpty();
    assertThat(response.createdAtUnixMs()).isEqualTo(100L);
    assertThat(response.updatedAtUnixMs()).isEqualTo(100L);
  }
}
