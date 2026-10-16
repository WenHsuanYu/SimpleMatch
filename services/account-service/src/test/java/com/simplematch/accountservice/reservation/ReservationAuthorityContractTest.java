package com.simplematch.accountservice.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.simplematch.accountservice.authority.AccountLimitLedger;
import com.simplematch.accountservice.authority.ReservationOutcome;
import com.simplematch.accountservice.authority.ReservationStatus;
import com.simplematch.contracts.common.v2.Side;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ReservationAuthorityContractTest {
  @ParameterizedTest
  @ValueSource(strings = {" ", "\t\n", "\u3000"})
  @DisplayName("active outcomes reject whitespace instead of storing it as an absent reason")
  void rejectsWhitespaceActiveReasons(String whitespace) {
    for (ReservationStatus status : new ReservationStatus[] {
        ReservationStatus.RESERVATION_STATUS_ACCEPTED,
        ReservationStatus.RESERVATION_STATUS_APPLIED}) {
      assertThatThrownBy(() -> new ReservationOutcome(status, whitespace, ""))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> new ReservationOutcome(status, "", whitespace))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"", " ", "\t", "lower", "BAD-CODE", " BAD", "BAD ", "BAD\n",
      "BAD\r", "1BAD", "失敗"})
  @DisplayName("rejection reason codes are stable uppercase ASCII tokens")
  void rejectsMalformedRejectionCodes(String code) {
    assertThatThrownBy(() -> ReservationOutcome.rejected(code, "Reservation unavailable"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest
  @ValueSource(strings = {"A", "A0_", "LIMIT_PRICE_REQUIRED",
      "INSUFFICIENT_AVAILABLE_NOTIONAL", "INSUFFICIENT_AVAILABLE_POSITION"})
  @DisplayName("rejection outcomes preserve canonical codes and valid detail without trimming")
  void preservesCanonicalRejectionReasons(String code) {
    final ReservationOutcome outcome = ReservationOutcome.rejected(code, "  固定系統訊息  ");

    assertThat(outcome.reasonCode()).isEqualTo(code);
    assertThat(outcome.reasonText()).isEqualTo("  固定系統訊息  ");
  }

  @ParameterizedTest
  @ValueSource(strings = {"", " ", "\t\n", "\u001c", "\u2003", "\u3000"})
  @DisplayName("the application rejects blank rejection detail independently of SQL length checks")
  void rejectsBlankRejectionDetail(String text) {
    assertThatThrownBy(() -> ReservationOutcome.rejected("REJECTED", text))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("active factory outcomes and null construction store exact empty reason strings")
  void normalizesAbsentActiveReasons() {
    assertThat(ReservationOutcome.accepted().reasonCode()).isEmpty();
    assertThat(ReservationOutcome.accepted().reasonText()).isEmpty();
    assertThat(ReservationOutcome.applied().reasonCode()).isEmpty();
    assertThat(ReservationOutcome.applied().reasonText()).isEmpty();
    for (ReservationStatus status : new ReservationStatus[] {
        ReservationStatus.RESERVATION_STATUS_ACCEPTED,
        ReservationStatus.RESERVATION_STATUS_APPLIED}) {
      final ReservationOutcome outcome = new ReservationOutcome(status, null, null);
      assertThat(outcome.reasonCode()).isEmpty();
      assertThat(outcome.reasonText()).isEmpty();
    }
  }

  @Test
  @DisplayName("null rejection reasons are invalid while released reason semantics stay unchanged")
  void retainsNullAndReleaseSemantics() {
    assertThatThrownBy(() -> ReservationOutcome.rejected(null, "Detail"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ReservationOutcome.rejected("REJECTED", null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(ReservationOutcome.released(null).reasonCode()).isEmpty();
    assertThat(ReservationOutcome.released("MATCHING_CANCELLED").reasonText()).isEmpty();
    final ReservationOutcome released = new ReservationOutcome(
        ReservationStatus.RESERVATION_STATUS_RELEASED, "caller code", "\t");
    assertThat(released.reasonCode()).isEqualTo("caller code");
    assertThat(released.reasonText()).isEqualTo("\t");
  }

  @Test
  @DisplayName("request, order and reservation identities reject non UUID strings internally")
  void rejectsMalformedIdentities() {
    assertThatThrownBy(() -> new ReservationRequestIdentity.RequestId("request-1"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ReservationRequestIdentity.OrderId("order-1"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ReservationIdentity.ReservationId("reservation-1"))
        .isInstanceOf(IllegalArgumentException.class);
    final String id = UUID.randomUUID().toString();
    assertThat(new ReservationIdentity.OrderId(id).wireValue()).isEqualTo(id);
  }

  @Test
  @DisplayName("only TWSE and TPEx venue MICs may reserve authority")
  void restrictsVenue() {
    assertThat(new ReservationTerms.VenueMic("xtai").value()).isEqualTo("XTAI");
    assertThat(new ReservationTerms.VenueMic("ROCO").value()).isEqualTo("ROCO");
    assertThatThrownBy(() -> new ReservationTerms.VenueMic("LEGACY"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ReservationTerms.VenueMic("XNYS"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("price precision and range match signed long fixed point V2 units")
  void restrictsPricePrecisionAndRange() {
    assertThatThrownBy(() -> new ReservationTerms.LimitPrice(new BigDecimal("1.00001")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new ReservationTerms.LimitPrice(new BigDecimal("922337203685477.5808")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(new ReservationTerms.LimitPrice(new BigDecimal("922337203685477.5807")).value())
        .isEqualByComparingTo("922337203685477.5807");
  }

  @Test
  @DisplayName("reserve operations require the business day instead of inferring today's date")
  void requiresTradingDay() {
    final ReservationRequestIdentity identity =
        new ReservationRequestIdentity(
            new ReservationRequestIdentity.RequestId(UUID.randomUUID()),
            new ReservationRequestIdentity.OrderId(UUID.randomUUID()),
            new ReservationRequestIdentity.AccountId(UUID.randomUUID().toString()));
    final ReservationTerms terms = terms(1, "1");

    assertThatThrownBy(() -> new ReserveOperation(identity, terms, null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("tradingDay");
    final LocalDate historicalDay = LocalDate.of(2026, 7, 28);
    assertThat(new ReserveOperation(identity, terms, historicalDay).tradingDay())
        .isEqualTo(historicalDay);
  }

  @Test
  @DisplayName("price times whole shares must fit the lifecycle notional contract before reserve")
  void rejectsReservationNotionalOverflow() {
    assertThat(terms(1, "922337203685477.5807").quantity().value()).isOne();
    assertThatThrownBy(() -> terms(2, "922337203685477.5807"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("notional exceeds fixed-point range");
    assertThatThrownBy(() -> new ReservationTerms.ReservationQuantity(0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ReservationTerms.ReservationQuantity(-1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("execution notional is exact and bounded before authoritative mutation")
  void rejectsFillNotionalOverflowAndPrecision() {
    assertThatThrownBy(() -> new ExecutionFill(
            new ExecutionFill.ExecutionId(UUID.randomUUID().toString()),
            ExecutionFill.AggregateSequence.absent(), new ExecutionFill.FillQuantity(2),
            new ExecutionFill.FillPrice(new BigDecimal("922337203685477.5807"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("fill_notional exceeds fixed-point range");
    assertThatThrownBy(() -> new ExecutionFill.FillPrice(new BigDecimal("0.00001")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("account aggregates retain exact fixed point sums beyond one reservation range")
  void retainsWideAggregateWithoutRounding() {
    final BigDecimal total = new BigDecimal("1844674407370955.1614");
    assertThat(new AccountLimitLedger(total, BigDecimal.ZERO, BigDecimal.ZERO, total)
            .availableNotional())
        .isEqualByComparingTo(total);
    assertThatThrownBy(
            () -> new AccountLimitLedger(new BigDecimal("0.00001"), BigDecimal.ZERO,
                BigDecimal.ZERO, new BigDecimal("0.00001")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private ReservationTerms terms(long quantity, String price) {
    return new ReservationTerms(
        new ReservationTerms.InstrumentSymbol("2330"), new ReservationTerms.VenueMic("XTAI"),
        Side.SIDE_BUY, new ReservationTerms.ReservationQuantity(quantity),
        new ReservationTerms.LimitPrice(new BigDecimal(price)));
  }
}
