package com.simplematch.accountservice.authority;

/**
 * Optimistic version and audit timestamps of one reservation lifecycle.
 *
 * @param version optimistic concurrency version
 * @param createdAtUnixMs creation timestamp in Unix milliseconds
 * @param updatedAtUnixMs latest update timestamp in Unix milliseconds
 */
public record ReservationRevision(long version, long createdAtUnixMs, long updatedAtUnixMs) {
  /** Requires monotonic, non-negative lifecycle timestamps and version. */
  public ReservationRevision {
    if (version < 0 || createdAtUnixMs < 0 || updatedAtUnixMs < createdAtUnixMs) {
      throw new IllegalArgumentException("reservation version and timestamps are invalid");
    }
  }

  /** Returns the first revision created at the supplied timestamp. */
  public static ReservationRevision initial(long now) {
    return new ReservationRevision(0, now, now);
  }

  /** Returns the next optimistic revision while retaining the creation timestamp. */
  public ReservationRevision next(long now) {
    return new ReservationRevision(version + 1, createdAtUnixMs, now);
  }
}
