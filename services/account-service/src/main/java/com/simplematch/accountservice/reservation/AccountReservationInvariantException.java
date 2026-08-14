package com.simplematch.accountservice.reservation;

/** Indicates that an authoritative Account reservation invariant could not be maintained. */
public final class AccountReservationInvariantException extends IllegalStateException {
  /**
   * Creates an invariant failure with a diagnostic message.
   *
   * @param message diagnostic invariant failure message
   */
  public AccountReservationInvariantException(String message) {
    super(message);
  }
}
