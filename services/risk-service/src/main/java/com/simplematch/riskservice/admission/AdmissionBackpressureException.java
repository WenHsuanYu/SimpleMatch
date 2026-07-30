package com.simplematch.riskservice.admission;

/** Indicates that durable publication lag has reached the admission safety bound. */
public final class AdmissionBackpressureException extends RuntimeException {

  private final Reason reason;

  /**
   * Creates a backpressure failure without an underlying exception.
   *
   * @param reason stable machine-readable failure reason
   * @param message diagnostic message without sensitive data
   */
  public AdmissionBackpressureException(Reason reason, String message) {
    super(message);
    this.reason = reason;
  }

  /**
   * Creates a backpressure failure caused by an infrastructure error.
   *
   * @param reason stable machine-readable failure reason
   * @param message diagnostic message without sensitive data
   * @param cause original infrastructure failure
   */
  public AdmissionBackpressureException(Reason reason, String message, Throwable cause) {
    super(message, cause);
    this.reason = reason;
  }

  /**
   * Returns the stable reason used for transport mapping, metrics, and alerts.
   *
   * @return stable backpressure reason
   */
  public Reason reason() {
    return reason;
  }

  /** Stable categories for admission backpressure failures. */
  public enum Reason {
    /** The observed backlog is greater than the configured safe bound. */
    BACKLOG_EXCEEDED,

    /** The expected durable metric row does not exist. */
    METRIC_MISSING,

    /** The durable metric contains invalid or contradictory data. */
    METRIC_INVALID,

    /** The durable metric has not been refreshed recently enough. */
    METRIC_STALE,

    /** The durable metric cannot be queried because its storage is unavailable. */
    METRIC_UNAVAILABLE
  }
}
