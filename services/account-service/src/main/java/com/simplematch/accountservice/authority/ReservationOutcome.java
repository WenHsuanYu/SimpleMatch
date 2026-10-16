package com.simplematch.accountservice.authority;

import java.util.Objects;

/**
 * State-specific outcome and reason of one reservation lifecycle.
 * Released outcomes retain the caller's reason without imposing rejection requirements.
 *
 * @param status lifecycle status
 * @param reasonCode stable ASCII token for rejection; empty for accepted or applied outcomes
 * @param reasonText nonblank rejection detail; empty for accepted or applied outcomes
 */
public record ReservationOutcome(
    ReservationStatus status, String reasonCode, String reasonText) {
  /** Normalizes null reasons to empty strings and enforces status-specific reason contracts. */
  public ReservationOutcome {
    Objects.requireNonNull(status, "status");
    reasonCode = Objects.requireNonNullElse(reasonCode, "");
    reasonText = Objects.requireNonNullElse(reasonText, "");
    if (status == ReservationStatus.RESERVATION_STATUS_REJECTED
        && (!reasonCode.matches("[A-Z][A-Z0-9_]*") || reasonText.isBlank())) {
      throw new IllegalArgumentException("rejected reservation requires a stable reason");
    }
    if ((status == ReservationStatus.RESERVATION_STATUS_ACCEPTED
            || status == ReservationStatus.RESERVATION_STATUS_APPLIED)
        && (!reasonCode.isEmpty() || !reasonText.isEmpty())) {
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
