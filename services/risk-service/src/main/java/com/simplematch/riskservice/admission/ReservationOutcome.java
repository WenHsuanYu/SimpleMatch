package com.simplematch.riskservice.admission;

import java.util.UUID;

/**
 * Idempotent account reservation response returned by the risk adapter.
 */
public record ReservationOutcome(boolean accepted, UUID reservationId, String reasonCode, String reasonDetail) {
    /**
     * Creates an accepted account result.
     */
    public static ReservationOutcome accepted(UUID reservationId) {
        return new ReservationOutcome(true, reservationId, "", "");
    }

    /**
     * Creates a stable rejected account result.
     */
    public static ReservationOutcome rejected(String reasonCode, String reasonDetail) {
        return new ReservationOutcome(false, null, reasonCode, reasonDetail);
    }
}
