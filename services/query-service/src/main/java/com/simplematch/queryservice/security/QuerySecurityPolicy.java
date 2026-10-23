package com.simplematch.queryservice.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.security.authentication.BadCredentialsException;

/** Verifies provisioned opaque credentials against one immutable Query-local policy. */
public final class QuerySecurityPolicy {
  private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{43}");
  private final List<VerifiedCredential> credentials;
  private final Clock clock;

  /** Creates a fixed policy; replacing or revoking it requires the controlled restart contract. */
  public QuerySecurityPolicy(QuerySecurityProperties properties, Clock clock) {
    this.clock = clock;
    final Map<String, QueryPrincipal> activePrincipals =
        properties.principals().stream()
            .filter(
                principal -> principal.status() == QuerySecurityProperties.PrincipalStatus.ACTIVE)
            .map(
                principal ->
                    new QueryPrincipal(
                        principal.id(),
                        properties.policyVersion(),
                        principal.accountGrants().stream()
                            .map(UUID::fromString)
                            .collect(Collectors.toUnmodifiableSet()),
                        principal.operationsRead()))
            .collect(Collectors.toUnmodifiableMap(QueryPrincipal::id, Function.identity()));
    credentials =
        properties.credentials().stream()
            .map(
                credential ->
                    new VerifiedCredential(
                        HexFormat.of().parseHex(credential.sha256()),
                        activePrincipals.get(credential.principalId()),
                        credential.enabled(),
                        credential.expiresAt()))
            .toList();
  }

  /**
   * Establishes identity only from a canonical opaque credential; never from account selectors.
   *
   * @param token the externally provisioned HTTP Bearer value, never an operator or FIX secret
   * @return the registered active principal and its coherent account/operations entitlements
   * @throws BadCredentialsException if the credential is invalid, expired or disabled
   */
  public QueryPrincipal authenticate(String token) {
    requireCanonicalToken(token);
    final byte[] digest = digest(token);
    VerifiedCredential matched = null;
    for (VerifiedCredential credential : credentials) {
      if (credential.matches(digest)) {
        matched = credential;
      }
    }
    if (matched == null) {
      throw invalidCredential();
    }
    return matched.principalAt(clock.instant());
  }

  private void requireCanonicalToken(String token) {
    if (token == null || !TOKEN.matcher(token).matches()) {
      throw invalidCredential();
    }
    final byte[] decoded = Base64.getUrlDecoder().decode(token);
    if (decoded.length != 32
        || !Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(token)) {
      throw invalidCredential();
    }
  }

  private byte[] digest(String token) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII));
    } catch (NoSuchAlgorithmException failure) {
      throw new IllegalStateException("SHA-256 is required by the Query runtime", failure);
    }
  }

  private static BadCredentialsException invalidCredential() {
    return new BadCredentialsException("Query credential is invalid");
  }

  private static final class VerifiedCredential {
    private final byte[] sha256;
    private final QueryPrincipal principal;
    private final boolean enabled;
    private final Instant expiresAt;

    private VerifiedCredential(
        byte[] sha256, QueryPrincipal principal, boolean enabled, Instant expiresAt) {
      this.sha256 = sha256.clone();
      this.principal = principal;
      this.enabled = enabled;
      this.expiresAt = expiresAt;
    }

    private boolean matches(byte[] candidate) {
      return MessageDigest.isEqual(sha256, candidate);
    }

    private QueryPrincipal principalAt(Instant now) {
      if (!enabled || principal == null || !now.isBefore(expiresAt)) {
        throw invalidCredential();
      }
      return principal;
    }
  }
}
