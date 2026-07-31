package com.simplematch.accountservice.authority;

import com.simplematch.contracts.common.v1.ReservationStatus;
import java.util.Objects;

/**
 * State-specific outcome and reason of one reservation lifecycle.
 *
 * @param status lifecycle status
 * @param reasonCode stable machine-readable reason
 * @param reasonText human-readable reason detail
 */
public record ReservationOutcome(
    ReservationStatus status, String reasonCode, String reasonText) {
  /** Enforces legal reason combinations for each reservation status. */
  public ReservationOutcome {
    Objects.requireNonNull(status, "status");
    reasonCode = Objects.requireNonNullElse(reasonCode, "");
    reasonText = Objects.requireNonNullElse(reasonText, "");
    if (status == ReservationStatus.RESERVATION_STATUS_UNSPECIFIED) {
      throw new IllegalArgumentException("status must be specified");
    }
    if (status == ReservationStatus.RESERVATION_STATUS_REJECTED
        && (reasonCode.isBlank() || reasonText.isBlank())) {
      throw new IllegalArgumentException("rejected reservation requires a stable reason");
    }
    if ((status == ReservationStatus.RESERVATION_STATUS_ACCEPTED
            || status == ReservationStatus.RESERVATION_STATUS_APPLIED)
        && (!reasonCode.isBlank() || !reasonText.isBlank())) {
      throw new IllegalArgumentException("active reservation must not carry a reason");
    }
  }

  /** Returns an active outcome without a rejection reason. */
  public static ReservationOutcome accepted() {
    return new ReservationOutcome(
        ReservationStatus.RESERVATION_STATUS_ACCEPTED, "", "");
  }

  /** Returns a rejected outcome with a stable code and detail. */
  public static ReservationOutcome rejected(String reasonCode, String reasonText) {
    return new ReservationOutcome(
        ReservationStatus.RESERVATION_STATUS_REJECTED, reasonCode, reasonText);
  }

  /** Returns a released outcome retaining the caller's release reason code. */
  public static ReservationOutcome released(String reasonCode) {
    return new ReservationOutcome(
        ReservationStatus.RESERVATION_STATUS_RELEASED, reasonCode, "");
  }

  /** Returns a fully applied outcome without a rejection reason. */
  public static ReservationOutcome applied() {
    return new ReservationOutcome(ReservationStatus.RESERVATION_STATUS_APPLIED, "", "");
  }
}
