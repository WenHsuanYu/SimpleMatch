package com.simplematch.tools.kubernetes;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Provisions five stable run-owned Principals with rotating credentials for both FIX Accounts. */
final class KubernetesQueryCredentials {
  private static final String PREFIX = "simplematch.query-service.security.";
  private static final SecureRandom RANDOM = new SecureRandom();
  private final UUID account;
  private final UUID seller;
  private final String reader = token();
  private final String operations = token();
  private final String clientA = token();
  private final String clientB = token();
  private final String clientD = token();
  private final UUID readerPrincipal = UUID.randomUUID();
  private final UUID operationsPrincipal = UUID.randomUUID();
  private final UUID retainedReaderPrincipal = UUID.randomUUID();
  private final UUID sellerPrincipal = UUID.randomUUID();
  private final UUID ungrantedPrincipal = UUID.randomUUID();
  private final Instant expiresAt = Instant.now().plus(Duration.ofHours(6));

  KubernetesQueryCredentials(UUID account) {
    this(account, UUID.randomUUID());
  }

  KubernetesQueryCredentials(UUID account, UUID seller) {
    this.account = java.util.Objects.requireNonNull(account);
    this.seller = java.util.Objects.requireNonNull(seller);
    if (account.equals(seller)) {
      throw new IllegalArgumentException("the Query C/Y matrix needs distinct Accounts");
    }
  }

  String operationsToken() {
    return operations;
  }

  String readerToken() {
    return reader;
  }

  Map<String, String> tokens() {
    return tokens(clientA, clientB, reader, operations, clientD);
  }

  private static Map<String, String> tokens(String a, String b, String c, String ops, String d) {
    return Map.of("client-a", a, "client-b", b, "client-c", c,
        "operations", ops, "client-d", d);
  }

  Map<String, String> secret(String dsn) throws Exception {
    return secret(dsn, 1, tokens());
  }

  private Map<String, String> secret(String dsn, int version, Map<String, String> tokens)
      throws Exception {
    final var properties = new StringBuilder();
    properties.append(PREFIX).append("schema-version=1\n");
    properties.append(PREFIX).append("policy-version=").append(version).append('\n');
    principal(properties, 0, readerPrincipal, false,
        version == 1 ? List.of(account, seller) : List.of(account), false);
    principal(properties, 1, operationsPrincipal, true, List.of(), false);
    principal(properties, 2, retainedReaderPrincipal, false, List.of(account), false);
    principal(properties, 3, sellerPrincipal, false, List.of(seller), false);
    principal(properties, 4, ungrantedPrincipal, false, List.of(), version == 3);
    credential(properties, 0, readerPrincipal, tokens.get("client-c"));
    credential(properties, 1, operationsPrincipal, tokens.get("operations"));
    credential(properties, 2, retainedReaderPrincipal, tokens.get("client-a"));
    credential(properties, 3, sellerPrincipal, tokens.get("client-b"));
    credential(properties, 4, ungrantedPrincipal, tokens.get("client-d"));
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
    final var current = tokens(nextRetainedReader, token(), nextReader, nextOperations, token());
    return new Generation(version, nextReader, nextOperations, nextRetainedReader,
        current, secret(dsn, version, current));
  }

  private static void principal(StringBuilder output, int index, UUID identity,
      boolean operationsRead, List<UUID> grants, boolean disabled) {
    final String prefix = PREFIX + "principals[" + index + "].";
    output.append(prefix).append("id=").append(identity).append('\n');
    output.append(prefix).append(disabled ? "status=DISABLED\n" : "status=ACTIVE\n");
    output.append(prefix).append("operations-read=").append(operationsRead).append('\n');
    for (int position = 0; position < grants.size(); position++) {
      output.append(prefix).append("account-grants[").append(position).append("]=")
          .append(grants.get(position)).append('\n');
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
      String retainedReaderToken, Map<String, String> tokens,
      Map<String, String> secret) {
    Generation {
      tokens = Map.copyOf(tokens);
      secret = Map.copyOf(secret);
    }

    @Override
    public String toString() {
      return "Generation[version=" + version + ", credentials/secret=<redacted>]";
    }
  }
}
