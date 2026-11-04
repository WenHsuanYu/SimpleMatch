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

  @Test
  void replacementRevokesGrantThenDisablesPrincipalWithRotatedCredentials() throws Exception {
    final UUID account = UUID.randomUUID();
    final var credentials = new KubernetesQueryCredentials(account);
    final String baseline = credentials.secret("postgres://localhost/test")
        .get("query-service.properties");
    final var version2 = credentials.replacement(2, "postgres://localhost/test");
    final var version3 = credentials.replacement(3, "postgres://localhost/test");
    final String v2 = version2.secret().get("query-service.properties");
    final String v3 = version3.secret().get("query-service.properties");
    assertTrue(v2.contains("security.policy-version=2"));
    assertTrue(v3.contains("security.policy-version=3"));
    assertFalse(v2.contains("principals[0].account-grants[0]=" + account));
    assertTrue(v2.contains("principals[0].status=ACTIVE"));
    assertTrue(v3.contains("principals[0].status=DISABLED"));
    assertTrue(v2.contains("principals[2].account-grants[0]=" + account));
    assertTrue(v3.contains("principals[2].account-grants[0]=" + account));
    assertTrue(v3.contains("principals[2].status=ACTIVE"));
    assertEquals(baseline.lines().filter(line -> line.contains("principals[0].id=")).findFirst(),
        v3.lines().filter(line -> line.contains("principals[0].id=")).findFirst());
    assertFalse(version2.readerToken().equals(credentials.readerToken()));
    assertFalse(version3.readerToken().equals(version2.readerToken()));
    assertFalse(version2.operationsToken().equals(version3.operationsToken()));
    for (var generation : java.util.List.of(version2, version3)) {
      final String policy = generation.secret().get("query-service.properties");
      assertFalse(policy.contains(generation.readerToken()));
      assertFalse(policy.contains(generation.operationsToken()));
      assertFalse(policy.contains(generation.retainedReaderToken()));
      assertFalse(generation.toString().contains(generation.readerToken()));
      assertFalse(generation.toString().contains(generation.operationsToken()));
      assertFalse(generation.toString().contains(generation.retainedReaderToken()));
      assertFalse(generation.toString().contains("postgres://"));
    }
  }
}
