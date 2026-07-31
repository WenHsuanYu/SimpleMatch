package com.simplematch.accountservice.authority;

/**
 * Optimistic version and update timestamp of one account position.
 *
 * @param version optimistic concurrency version
 * @param updatedAtUnixMs latest update timestamp in Unix milliseconds
 */
public record AccountPositionRevision(long version, long updatedAtUnixMs) {
  /** Requires non-negative optimistic version and timestamp. */
  public AccountPositionRevision {
    if (version < 0 || updatedAtUnixMs < 0) {
      throw new IllegalArgumentException("version and timestamp must be non-negative");
    }
  }

  /** Returns the initial revision for a newly provisioned position. */
  public static AccountPositionRevision initial(long now) {
    return new AccountPositionRevision(0, now);
  }

  /** Returns the next optimistic revision. */
  public AccountPositionRevision next(long now) {
    return new AccountPositionRevision(version + 1, now);
  }
}
