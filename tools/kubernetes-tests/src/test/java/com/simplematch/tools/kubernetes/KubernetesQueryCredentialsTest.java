package com.simplematch.tools.kubernetes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Protects the run-scoped Query startup policy without retaining bearer material in reports. */
class KubernetesQueryCredentialsTest {
  @Test
  void separatesOperationsAuthorityFromAccountGrantAndStoresOnlyVerifiers() throws Exception {
    final UUID account = UUID.randomUUID();
    final var credentials = new KubernetesQueryCredentials(account);
    final var secret = credentials.secret("postgres://localhost/test");
    final String policy = secret.get("query-service.properties");
    assertEquals("postgres://localhost/test", secret.get("postgres_dsn"));
    assertTrue(policy.contains("principals[0].account-grants[0]=" + account));
    assertTrue(policy.contains("principals[0].operations-read=false"));
    assertTrue(policy.contains("principals[1].operations-read=true"));
    assertFalse(policy.contains("principals[1].account-grants"));
    assertFalse(policy.contains(credentials.operationsToken()));
    assertFalse(policy.contains(credentials.readerToken()));
    assertFalse(credentials.readerToken().equals(credentials.operationsToken()));
    for (int i = 0; i < 2; i++) {
      final String token = i == 0 ? credentials.readerToken() : credentials.operationsToken();
      final String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(token.getBytes(StandardCharsets.US_ASCII)));
      assertTrue(policy.contains("credentials[" + i + "].sha256=" + hash));
      assertEquals(43, token.length());
    }
  }
}
