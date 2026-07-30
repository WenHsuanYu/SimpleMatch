package com.simplematch.riskservice.admission;

import java.util.Objects;

/**
 * Stable domain failure explaining why an order could not enter durable admission.
 *
 * <p>Reason code and detail are distinct value types, so they cannot be exchanged at a call site.
 * Named factories expose the risk-service ubiquitous language for the currently supported
 * validation categories.
 *
 * @param reasonCode the machine-readable reason code
 * @param detail the client- and operator-readable detail
 */
public record AdmissionFailure(ReasonCode reasonCode, Detail detail) {
    /** Requires a complete typed failure description. */
    public AdmissionFailure {
        reasonCode = Objects.requireNonNull(reasonCode, "reasonCode");
        detail = Objects.requireNonNull(detail, "detail");
    }

    /** Creates a general invalid-command failure. */
    public static AdmissionFailure invalidCommand(String detail) {
        return new AdmissionFailure(new ReasonCode("INVALID_COMMAND"), new Detail(detail));
    }

    /** Creates an invalid-instrument failure. */
    public static AdmissionFailure invalidInstrument(String detail) {
        return new AdmissionFailure(new ReasonCode("INVALID_INSTRUMENT"), new Detail(detail));
    }

    /** Creates an unsupported-session failure. */
    public static AdmissionFailure unsupportedSession(String detail) {
        return new AdmissionFailure(new ReasonCode("UNSUPPORTED_SESSION"), new Detail(detail));
    }

    /** Stable machine-readable admission failure code. */
    public record ReasonCode(String value) {
        /** Requires a nonblank reason code. */
        public ReasonCode {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("reason_code must not be blank");
            }
        }
    }

    /** Human-readable admission failure detail. */
    public record Detail(String value) {
        /** Requires a nonblank failure detail. */
        public Detail {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("detail must not be blank");
            }
        }
    }
}
