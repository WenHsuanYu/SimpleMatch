package com.simplematch.quickfixgateway.fix;

import com.simplematch.contracts.v2.V2Identifiers;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import quickfix.SessionID;

/** Owns one provisioned FIX identity, credential digest, and immutable account authorization. */
public final class FixCounterparty {
  private final SessionID sessionId;
  private final String username;
  private final byte[] passwordDigest;
  private final Set<UUID> accountIds;

  /**
   * Creates a counterparty from secret configuration without retaining its plaintext password.
   *
   * @param sessionId complete acceptor-side SessionID, including any optional identity components
   * @param username expected Logon Username(553)
   * @param password expected Logon Password(554)
   * @param accountIds accounts this counterparty may operate
   * @throws IllegalArgumentException when identity, credentials, or account configuration is
   *     invalid
   */
  public FixCounterparty(
      String sessionId, String username, String password, Set<String> accountIds) {
    this.sessionId = new SessionID(requireValue(sessionId));
    if (!this.sessionId.toString().equals(sessionId)
        || this.sessionId.getSenderCompID().isBlank()
        || this.sessionId.getTargetCompID().isBlank()) {
      throw new IllegalArgumentException(
          "FIX counterparty requires a complete canonical SessionID");
    }
    this.username = requireValue(username);
    this.passwordDigest = digest(requireValue(password));
    this.accountIds =
        Objects.requireNonNull(accountIds, "accountIds").stream()
            .map(value -> V2Identifiers.AccountId.parse(value).value())
            .collect(Collectors.toUnmodifiableSet());
    if (this.accountIds.isEmpty()) {
      throw new IllegalArgumentException("FIX counterparty requires authorized accounts");
    }
  }

  SessionID sessionId() {
    return sessionId;
  }

  boolean authenticates(String suppliedUsername, String suppliedPassword) {
    return MessageDigest.isEqual(passwordDigest, digest(suppliedPassword))
        && username.equals(suppliedUsername);
  }

  boolean authorizes(UUID accountId) {
    return accountIds.contains(accountId);
  }

  /** Returns a diagnostic summary without exposing credentials or authorized account IDs. */
  @Override
  public String toString() {
    return "FixCounterparty[credentials=<redacted>, accountCount=" + accountIds.size() + "]";
  }

  private static String requireValue(String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("FIX counterparty identity and credentials are required");
    }
    return value;
  }

  private static byte[] digest(String value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException failure) {
      throw new IllegalStateException(
          "SHA-256 is required for FIX credential verification", failure);
    }
  }
}
