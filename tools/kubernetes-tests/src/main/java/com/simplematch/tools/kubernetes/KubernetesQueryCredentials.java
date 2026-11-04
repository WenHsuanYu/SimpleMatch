package com.simplematch.tools.kubernetes;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

/** Provisions one startup-only Query policy for the run-owned business account and operator. */
final class KubernetesQueryCredentials {
  private static final String PREFIX = "simplematch.query-service.security.";
  private static final SecureRandom RANDOM = new SecureRandom();
  private final UUID account;
  private final String reader = token();
  private final String operations = token();
  private final UUID readerPrincipal = UUID.randomUUID();
  private final UUID operationsPrincipal = UUID.randomUUID();
  private final UUID retainedReaderPrincipal = UUID.randomUUID();
  private final Instant expiresAt = Instant.now().plus(Duration.ofHours(6));

  KubernetesQueryCredentials(UUID account) {
    this.account = java.util.Objects.requireNonNull(account);
  }

  String operationsToken() {
    return operations;
  }

  String readerToken() {
    return reader;
  }

  Map<String, String> secret(String dsn) throws Exception {
    return secret(dsn, 1, reader, operations, null);
  }

  private Map<String, String> secret(String dsn, int version, String readerToken,
      String operationsToken, String retainedReaderToken) throws Exception {
    final var properties = new StringBuilder();
    properties.append(PREFIX).append("schema-version=1\n");
    properties.append(PREFIX).append("policy-version=").append(version).append('\n');
    principal(properties, 0, readerPrincipal, false,
        version == 1 ? account.toString() : null, version == 3);
    principal(properties, 1, operationsPrincipal, true, null);
    if (version >= 2) {
      principal(properties, 2, retainedReaderPrincipal, false, account.toString());
    }
    credential(properties, 0, readerPrincipal, readerToken);
    credential(properties, 1, operationsPrincipal, operationsToken);
    if (version >= 2) {
      credential(properties, 2, retainedReaderPrincipal, retainedReaderToken);
    }
    return Map.of("postgres_dsn", dsn, "query-service.properties", properties.toString());
  }

  /** Rotates credentials while preserving Principal identities across policy generations. */
  Generation replacement(int version, String dsn) throws Exception {
    if (version != 2 && version != 3) {
      throw new IllegalArgumentException("unsupported Query replacement generation");
    }
    final String nextReader = token();
    final String nextOperations = token();
    final String nextRetainedReader = token();
    return new Generation(version, nextReader, nextOperations, nextRetainedReader,
        secret(dsn, version, nextReader, nextOperations, nextRetainedReader));
  }

  private static void principal(StringBuilder output, int index, UUID identity,
      boolean operationsRead, String grant) {
    principal(output, index, identity, operationsRead, grant, false);
  }

  private static void principal(StringBuilder output, int index, UUID identity,
      boolean operationsRead, String grant, boolean disabled) {
    final String prefix = PREFIX + "principals[" + index + "].";
    output.append(prefix).append("id=").append(identity).append('\n');
    output.append(prefix).append(disabled ? "status=DISABLED\n" : "status=ACTIVE\n");
    output.append(prefix).append("operations-read=").append(operationsRead).append('\n');
    if (grant != null) {
      output.append(prefix).append("account-grants[0]=").append(grant).append('\n');
    }
  }

  private void credential(StringBuilder output, int index, UUID principal, String bearer)
      throws Exception {
    final String prefix = PREFIX + "credentials[" + index + "].";
    final var digest = MessageDigest.getInstance("SHA-256")
        .digest(bearer.getBytes(StandardCharsets.US_ASCII));
    output.append(prefix).append("id=").append(UUID.randomUUID()).append('\n');
    output.append(prefix).append("principal-id=").append(principal).append('\n');
    output.append(prefix).append("sha256=").append(HexFormat.of().formatHex(digest))
        .append('\n');
    output.append(prefix).append("enabled=true\n");
    output.append(prefix).append("expires-at=").append(expiresAt).append('\n');
  }

  private static String token() {
    final byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  /** Private credentials are returned only to the in-process live verifier, never to evidence. */
  record Generation(int version, String readerToken, String operationsToken,
      String retainedReaderToken,
      Map<String, String> secret) {
    Generation {
      secret = Map.copyOf(secret);
    }

    @Override
    public String toString() {
      return "Generation[version=" + version + ", credentials/secret=<redacted>]";
    }
  }
}
