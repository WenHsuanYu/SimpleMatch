package com.simplematch.queryservice.security;

import java.security.Principal;
import java.util.Set;
import java.util.UUID;

/** An authenticated client and its grants from one immutable, validated Query policy snapshot. */
public record QueryPrincipal(
    String id, long policyVersion, Set<UUID> accountGrants, boolean operationsRead)
    implements Principal {
  /** The explicit Query-local permission for internal checkpoint diagnostics. */
  public static final String OPERATIONS_READ = "operations-read";

  /** Takes an immutable copy so subsequent configuration changes cannot alter this identity. */
  public QueryPrincipal {
    accountGrants = Set.copyOf(accountGrants);
  }

  /** Returns the stable client identity independently of its current credential. */
  @Override
  public String getName() {
    return id;
  }

  /** Keeps account entitlements out of framework authentication diagnostics. */
  @Override
  public String toString() {
    return "QueryPrincipal[policyVersion=" + policyVersion + ", identity/grants=<redacted>]";
  }
}
