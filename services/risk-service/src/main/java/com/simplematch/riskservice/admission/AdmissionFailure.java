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
    Objects.requireNonNull(reasonCode, "reasonCode");
    Objects.requireNonNull(detail, "detail");
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

  /** Creates a fail-closed failure for a missing or inactive routing policy. */
  public static AdmissionFailure routingPolicyUnavailable(String detail) {
    return new AdmissionFailure(
        new ReasonCode("ROUTING_POLICY_UNAVAILABLE"), new Detail(detail));
  }

  /** Creates a fail-closed failure for an instrument absent from the active policy. */
  public static AdmissionFailure routingInstrumentNotAssigned(String detail) {
    return new AdmissionFailure(
        new ReasonCode("ROUTING_INSTRUMENT_NOT_ASSIGNED"), new Detail(detail));
  }

  /** Stable uppercase ASCII admission failure code, without a closed list of permitted codes. */
  public record ReasonCode(String value) {
    /**
     * Requires a code starting with an uppercase ASCII letter, followed by letters, digits,
     * or underscores.
     *
     * @param value the machine-readable code, preserved without normalization
     * @throws IllegalArgumentException if the value is null or does not match the code grammar
     */
    public ReasonCode {
      if (value == null || !value.matches("[A-Z][A-Z0-9_]*")) {
        throw new IllegalArgumentException("reason_code must match [A-Z][A-Z0-9_]*");
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
