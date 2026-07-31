package com.simplematch.accountservice.authority;

/**
 * Optimistic version and update timestamp of one account limit.
 *
 * @param version optimistic concurrency version
 * @param updatedAtUnixMs latest update timestamp in Unix milliseconds
 */
public record AccountLimitRevision(long version, long updatedAtUnixMs) {
  /** Requires non-negative optimistic version and timestamp. */
  public AccountLimitRevision {
    if (version < 0 || updatedAtUnixMs < 0) {
      throw new IllegalArgumentException("version and timestamp must be non-negative");
    }
  }

  /** Returns the initial revision for a newly provisioned limit. */
  public static AccountLimitRevision initial(long now) {
    return new AccountLimitRevision(0, now);
  }

  /** Returns the next optimistic revision. */
  public AccountLimitRevision next(long now) {
    return new AccountLimitRevision(version + 1, now);
  }
}
