package com.simplematch.accountservice.reservation;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.simplematch.contracts.common.v2.Side;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ReservationConstructorContractTest {
  private static final String ACCOUNT_ID = "0194a8f0-7c77-7b38-9e2d-2a5fdd0f7c13";

  @Test
  @DisplayName("reserve operations require both typed components")
  void reserveOperationRequiresTypedComponents() {
    assertThatThrownBy(() -> new ReserveOperation(null, terms(), java.time.LocalDate.of(2026, 7, 28)))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("identity");
    assertThatThrownBy(() -> new ReserveOperation(requestIdentity(), null, java.time.LocalDate.of(2026, 7, 28)))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("terms");
  }

  @Test
  @DisplayName("reservation request identities require all identifiers")
  void reservationRequestIdentityRequiresIdentifiers() {
    assertThatThrownBy(
            () ->
                new ReservationRequestIdentity(
                    null,
                    new ReservationRequestIdentity.OrderId("00000000-0000-0000-0000-000000000013"),
                    new ReservationRequestIdentity.AccountId(ACCOUNT_ID)))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("requestId");
    assertThatThrownBy(
            () ->
                new ReservationRequestIdentity(
                    new ReservationRequestIdentity.RequestId("00000000-0000-0000-0000-000000000011"),
                    null,
                    new ReservationRequestIdentity.AccountId(ACCOUNT_ID)))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("orderId");
    assertThatThrownBy(
            () ->
                new ReservationRequestIdentity(
                    new ReservationRequestIdentity.RequestId("00000000-0000-0000-0000-000000000011"),
                    new ReservationRequestIdentity.OrderId("00000000-0000-0000-0000-000000000013"),
                    null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("accountId");
  }

  @Test
  @DisplayName("reservation terms require all trading facts")
  void reservationTermsRequireTradingFacts() {
    assertThatThrownBy(
            () ->
                new ReservationTerms(
                    null,new ReservationTerms.VenueMic("XTAI"),
                    Side.SIDE_BUY,
                    new ReservationTerms.ReservationQuantity(10),
                    ReservationTerms.LimitPrice.absent()))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("symbol");
    assertThatThrownBy(
            () ->
                new ReservationTerms(
                    new ReservationTerms.InstrumentSymbol("2330"),new ReservationTerms.VenueMic("XTAI"),
                    null,
                    new ReservationTerms.ReservationQuantity(10),
                    ReservationTerms.LimitPrice.absent()))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("side");
    assertThatThrownBy(
            () ->
                new ReservationTerms(
                    new ReservationTerms.InstrumentSymbol("2330"),
                    new ReservationTerms.VenueMic("XTAI"),
                    Side.SIDE_BUY,
                    null,
                    ReservationTerms.LimitPrice.absent()))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("quantity");
    assertThatThrownBy(
            () ->
                new ReservationTerms(
                    new ReservationTerms.InstrumentSymbol("2330"),
                    new ReservationTerms.VenueMic("XTAI"),
                    Side.SIDE_BUY,
                    new ReservationTerms.ReservationQuantity(10),
                    null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("limitPrice");
  }

  @Test
  @DisplayName("release operations require identity and reason")
  void releaseOperationRequiresComponents() {
    assertThatThrownBy(
            () ->
                new ReleaseReservationOperation(
                    null, new ReleaseReservationOperation.ReleaseReason("CANCELLED")))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("reservation");
    assertThatThrownBy(() -> new ReleaseReservationOperation(reservationIdentity(), null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("reason");
  }

  @Test
  @DisplayName("reservation identities require all identifiers")
  void reservationIdentityRequiresIdentifiers() {
    assertThatThrownBy(
            () ->
                new ReservationIdentity(
                    null,
                    new ReservationIdentity.ReservationId("00000000-0000-0000-0000-000000000012"),
                    new ReservationIdentity.OrderId("00000000-0000-0000-0000-000000000013")))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("requestId");
    assertThatThrownBy(
            () ->
                new ReservationIdentity(
                    new ReservationIdentity.RequestId("00000000-0000-0000-0000-000000000011"),
                    null,
                    new ReservationIdentity.OrderId("00000000-0000-0000-0000-000000000013")))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("reservationId");
    assertThatThrownBy(
            () ->
                new ReservationIdentity(
                    new ReservationIdentity.RequestId("00000000-0000-0000-0000-000000000011"),
                    new ReservationIdentity.ReservationId("00000000-0000-0000-0000-000000000012"),
                    null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("orderId");
  }

  @Test
  @DisplayName("execution fills require all execution facts")
  void executionFillRequiresFacts() {
    assertThatThrownBy(() -> new ExecutionFill(null, sequence(), quantity(), price()))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("executionId");
    assertThatThrownBy(() -> new ExecutionFill(executionId(), null, quantity(), price()))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("aggregateSequence");
    assertThatThrownBy(() -> new ExecutionFill(executionId(), sequence(), null, price()))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("quantity");
    assertThatThrownBy(() -> new ExecutionFill(executionId(), sequence(), quantity(), null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("price");
  }

  @Test
  @DisplayName("fill operations require reservation and execution values")
  void applyFillOperationRequiresComponents() {
    assertThatThrownBy(() -> new ApplyFillOperation(null, fill()))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("reservation");
    assertThatThrownBy(() -> new ApplyFillOperation(reservationIdentity(), null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("fill");
  }

  private ReservationRequestIdentity requestIdentity() {
    return new ReservationRequestIdentity(
        new ReservationRequestIdentity.RequestId("00000000-0000-0000-0000-000000000011"),
        new ReservationRequestIdentity.OrderId("00000000-0000-0000-0000-000000000013"),
        new ReservationRequestIdentity.AccountId(ACCOUNT_ID));
  }

  private ReservationTerms terms() {
    return new ReservationTerms(
        new ReservationTerms.InstrumentSymbol("2330"),
        new ReservationTerms.VenueMic("XTAI"),
        Side.SIDE_BUY,
        new ReservationTerms.ReservationQuantity(10),
        new ReservationTerms.LimitPrice(new BigDecimal("100")));
  }

  private ReservationIdentity reservationIdentity() {
    return new ReservationIdentity(
        new ReservationIdentity.RequestId("00000000-0000-0000-0000-000000000011"),
        new ReservationIdentity.ReservationId("00000000-0000-0000-0000-000000000012"),
        new ReservationIdentity.OrderId("00000000-0000-0000-0000-000000000013"));
  }

  private ExecutionFill fill() {
    return new ExecutionFill(executionId(), sequence(), quantity(), price());
  }

  private ExecutionFill.ExecutionId executionId() {
    return new ExecutionFill.ExecutionId("execution-1");
  }

  private ExecutionFill.AggregateSequence sequence() {
    return ExecutionFill.AggregateSequence.absent();
  }

  private ExecutionFill.FillQuantity quantity() {
    return new ExecutionFill.FillQuantity(10);
  }

  private ExecutionFill.FillPrice price() {
    return new ExecutionFill.FillPrice(new BigDecimal("99"));
  }
}
