package com.simplematch.accountservice.authority;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.simplematch.accountservice.reservation.ExecutionFill;
import com.simplematch.accountservice.reservation.ReleaseReservationOperation;
import com.simplematch.accountservice.reservation.ReservationTerms;
import com.simplematch.contracts.common.v1.ReservationStatus;
import com.simplematch.contracts.common.v1.Side;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Tests the state-specific invariants of the account reservation lifecycle value. */
class ReservationLifecycleTest {
  @Test
  @DisplayName("rejected lifecycle has no held authority and cannot be filled")
  void rejectedLifecycleHasNoHeldAuthorityAndCannotBeFilled() {
    final ReservationLifecycle rejected =
        ReservationLifecycle.rejected(terms(), "LIMIT", "limit unavailable", 100L);

    assertThat(rejected.allocation().remainingQuantity()).isEqualByComparingTo("10");
    assertThat(rejected.allocation().filledQuantity()).isZero();
    assertThat(rejected.allocation().reservedNotional()).isZero();
    assertThatThrownBy(() -> rejected.applyFill(fill("1", "99"), terms(), 101L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("reservation is not active");
  }

  @Test
  @DisplayName("release ends unused authority but preserves filled quantity")
  void releaseEndsUnusedAuthorityButPreservesFilledQuantity() {
    final ReservationLifecycle partiallyFilled =
        ReservationLifecycle.accepted(terms(), new BigDecimal("1000"), 100L)
            .applyFill(fill("4", "90"), terms(), 200L);

    final ReservationLifecycle released =
        partiallyFilled.release(new ReleaseReservationOperation.ReleaseReason("CANCELLED"), 300L);

    assertThat(released.outcome().status())
        .isEqualTo(ReservationStatus.RESERVATION_STATUS_RELEASED);
    assertThat(released.allocation().remainingQuantity()).isZero();
    assertThat(released.allocation().filledQuantity()).isEqualByComparingTo("4");
    assertThat(released.allocation().reservedNotional()).isZero();
  }

  @Test
  @DisplayName("complete fill becomes applied with no remaining authority")
  void completeFillBecomesAppliedWithNoRemainingAuthority() {
    final ReservationLifecycle applied =
        ReservationLifecycle.accepted(terms(), new BigDecimal("1000"), 100L)
            .applyFill(fill("10", "90"), terms(), 200L);

    assertThat(applied.outcome().status())
        .isEqualTo(ReservationStatus.RESERVATION_STATUS_APPLIED);
    assertThat(applied.allocation().remainingQuantity()).isZero();
    assertThat(applied.allocation().filledQuantity()).isEqualByComparingTo("10");
    assertThat(applied.allocation().reservedNotional()).isZero();
  }

  private ReservationTerms terms() {
    return new ReservationTerms(
        new ReservationTerms.InstrumentSymbol("2330"),
        Side.SIDE_BUY,
        new ReservationTerms.ReservationQuantity(new BigDecimal("10")),
        new ReservationTerms.LimitPrice(new BigDecimal("100")));
  }

  private ExecutionFill fill(String quantity, String price) {
    return new ExecutionFill(
        new ExecutionFill.ExecutionId(UUID.randomUUID().toString()),
        ExecutionFill.AggregateSequence.absent(),
        new ExecutionFill.FillQuantity(new BigDecimal(quantity)),
        new ExecutionFill.FillPrice(new BigDecimal(price)));
  }
}
