package com.simplematch.queryservice.security;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Defines one validated immutable Query identity and grant policy from Spring Environment. */
@ConfigurationProperties("simplematch.query-service.security")
public record QuerySecurityProperties(
    int schemaVersion,
    long policyVersion,
    List<PrincipalRegistration> principals,
    List<CredentialRegistration> credentials) {
  /** Rejects incomplete or ambiguous policy snapshots before they can authorize HTTP requests. */
  public QuerySecurityProperties {
    if (schemaVersion != 1) {
      throw new IllegalArgumentException("unsupported Query security policy schema");
    }
    if (policyVersion <= 0) {
      throw new IllegalArgumentException("Query security policy version must be positive");
    }
    requireEntries(principals);
    requireEntries(credentials);
    principals = List.copyOf(principals);
    credentials = List.copyOf(credentials);
    validateCredentials(credentials, principalIds(principals));
  }

  /** Prevents diagnostic rendering from exposing identity mappings, grants or verifiers. */
  @Override
  public String toString() {
    return "QuerySecurityProperties[schemaVersion="
        + schemaVersion
        + ", policyVersion="
        + policyVersion
        + ", policy=<redacted>]";
  }

  /** Distinguishes active identities from identities that may no longer authenticate. */
  public enum PrincipalStatus {
    /** Permits authentication using an active credential. */
    ACTIVE,
    /** Suspends authentication without transferring identity or grants. */
    DISABLED,
    /** Preserves the identity as retired within the policy lifecycle. */
    RETIRED
  }

  /** Registers a stable identity independently of credentials and explicit account grants. */
  public record PrincipalRegistration(
      String id, PrincipalStatus status, List<String> accountGrants, boolean operationsRead) {
    /**
     * Validates canonical grants and removes mutable references supplied by configuration binders.
     */
    public PrincipalRegistration {
      requireIdentity(id);
      if (status == null) {
        throw new IllegalArgumentException("Query principal status is required");
      }
      validateGrants(accountGrants);
      accountGrants = accountGrants == null ? List.of() : List.copyOf(accountGrants);
      if (status != PrincipalStatus.ACTIVE && !accountGrants.isEmpty()) {
        throw new IllegalArgumentException("inactive Query principal cannot retain account grants");
      }
    }

    /** Redacts identity and entitlements in configuration diagnostics. */
    @Override
    public String toString() {
      return "PrincipalRegistration[status=" + status + ", identity/grants=<redacted>]";
    }
  }

  /** Associates a credential verifier and expiry with one registered principal. */
  public record CredentialRegistration(
      String id, String principalId, String sha256, boolean enabled, Instant expiresAt) {
    /**
     * Requires verifier material without rejecting disabled or already-expired lifecycle records.
     */
    public CredentialRegistration {
      requireIdentity(id);
      requireIdentity(principalId);
      if (sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
        throw new IllegalArgumentException(
            "Query credential requires a lowercase SHA-256 verifier");
      }
      if (expiresAt == null) {
        throw new IllegalArgumentException("Query credential expiry is required");
      }
    }

    /** Never renders verifier material or credential-to-principal mappings. */
    @Override
    public String toString() {
      return "CredentialRegistration[enabled=" + enabled + ", identity/verifier=<redacted>]";
    }
  }

  private static void requireEntries(List<?> entries) {
    if (entries == null || entries.isEmpty() || entries.stream().anyMatch(value -> value == null)) {
      throw new IllegalArgumentException(
          "Query security policy requires complete nonempty registrations");
    }
  }

  private static Set<String> principalIds(List<PrincipalRegistration> principals) {
    final Set<String> principalIds = new HashSet<>();
    for (PrincipalRegistration principal : principals) {
      if (!principalIds.add(principal.id())) {
        throw new IllegalArgumentException("duplicate Query principal identity");
      }
    }
    return Set.copyOf(principalIds);
  }

  private static void validateCredentials(
      List<CredentialRegistration> credentials, Set<String> principalIds) {
    final Set<String> credentialIds = new HashSet<>();
    final Set<String> verifiers = new HashSet<>();
    for (CredentialRegistration credential : credentials) {
      if (!credentialIds.add(credential.id()) || !verifiers.add(credential.sha256())) {
        throw new IllegalArgumentException("duplicate Query credential identity or verifier");
      }
      if (!principalIds.contains(credential.principalId())) {
        throw new IllegalArgumentException("Query credential references an unknown principal");
      }
    }
  }

  private static void requireIdentity(String identity) {
    if (identity == null
        || identity.isBlank()
        || identity
            .codePoints()
            .anyMatch(value -> Character.isWhitespace(value) || Character.isISOControl(value))) {
      throw new IllegalArgumentException(
          "Query identity must be nonblank without whitespace or controls");
    }
  }

  private static void validateGrants(List<String> grants) {
    if (grants == null) {
      return;
    }
    final Set<String> unique = new HashSet<>();
    for (String grant : grants) {
      if (!canonicalAccount(grant)) {
        throw new IllegalArgumentException("Query account grant requires a canonical Account UUID");
      }
      if (!unique.add(grant)) {
        throw new IllegalArgumentException("duplicate Query account grant");
      }
    }
  }

  private static boolean canonicalAccount(String account) {
    if (account == null) {
      return false;
    }
    try {
      return UUID.fromString(account).toString().equals(account);
    } catch (IllegalArgumentException invalid) {
      return false;
    }
  }
}
