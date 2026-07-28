package com.simplematch.riskservice.admission;

/**
 * Indicates that an account reservation call failed and the pending saga remains recoverable.
 */
public final class AdmissionUnavailableException extends RuntimeException {
    /**
     * Creates an unavailable result with the original cause.
     */
    public AdmissionUnavailableException(Throwable cause) {
        super("account reservation is temporarily unavailable; admission remains pending", cause);
    }
}
