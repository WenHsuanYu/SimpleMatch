package com.simplematch.riskservice.admission;

/** Narrow remote account reservation port used outside risk database transactions. */
public interface AccountReservationClient {
  /** Reserves account authority for a pending admission. */
  ReservationOutcome reserve(AdmissionCommand command);
}
