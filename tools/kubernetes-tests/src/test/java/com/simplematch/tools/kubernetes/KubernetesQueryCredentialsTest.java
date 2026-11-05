package com.simplematch.tools.kubernetes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Protects the run-scoped Query startup policy without retaining bearer material in reports. */
class KubernetesQueryCredentialsTest {
  @Test
  void separatesAllFivePrincipalsAndStoresOnlyRotatableVerifiers() throws Exception {
    final UUID account = UUID.randomUUID();
    final UUID seller = UUID.randomUUID();
    final var credentials = new KubernetesQueryCredentials(account, seller);
    final var secret = credentials.secret("postgres://localhost/test");
    final String policy = secret.get("query-service.properties");
    final var tokens = credentials.tokens();
    assertEquals("postgres://localhost/test", secret.get("postgres_dsn"));
    assertTrue(policy.contains("principals[0].account-grants[0]=" + account));
    assertTrue(policy.contains("principals[0].account-grants[1]=" + seller));
    assertTrue(policy.contains("principals[0].operations-read=false"));
    assertTrue(policy.contains("principals[1].operations-read=true"));
    assertFalse(policy.contains("principals[1].account-grants"));
    assertTrue(policy.contains("principals[2].account-grants[0]=" + account));
    assertTrue(policy.contains("principals[3].account-grants[0]=" + seller));
    assertTrue(policy.contains("principals[4].status=ACTIVE"));
    assertFalse(policy.contains("principals[4].account-grants"));
    assertEquals(Set.copyOf(KubernetesQueryPermissionMatrix.ALIASES), tokens.keySet());
    assertEquals(5, Set.copyOf(tokens.values()).size());
    assertFalse(policy.contains(credentials.operationsToken()));
    assertFalse(policy.contains(credentials.readerToken()));
    assertFalse(credentials.readerToken().equals(credentials.operationsToken()));
    final List<String> aliases =
        List.of("client-c", "operations", "client-a", "client-b", "client-d");
    for (int i = 0; i < aliases.size(); i++) {
      final String token = tokens.get(aliases.get(i));
      final String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(token.getBytes(StandardCharsets.US_ASCII)));
      assertTrue(policy.contains("credentials[" + i + "].sha256=" + hash));
      assertFalse(policy.contains(token));
      assertEquals(43, token.length());
    }
  }

  @Test
  void replacementRevokesOnlyClientCYThenDisablesDWithAllCredentialsRotated() throws Exception {
    final UUID account = UUID.randomUUID();
    final UUID seller = UUID.randomUUID();
    final var credentials = new KubernetesQueryCredentials(account, seller);
    final String baseline = credentials.secret("postgres://localhost/test")
        .get("query-service.properties");
    final var version2 = credentials.replacement(2, "postgres://localhost/test");
    final var version3 = credentials.replacement(3, "postgres://localhost/test");
    final String v2 = version2.secret().get("query-service.properties");
    final String v3 = version3.secret().get("query-service.properties");
    assertTrue(v2.contains("security.policy-version=2"));
    assertTrue(v3.contains("security.policy-version=3"));
    assertTrue(v2.contains("principals[0].account-grants[0]=" + account));
    assertFalse(v2.contains("principals[0].account-grants[1]=" + seller));
    assertTrue(v2.contains("principals[0].status=ACTIVE"));
    assertTrue(v3.contains("principals[0].status=ACTIVE"));
    assertTrue(v3.contains("principals[0].account-grants[0]=" + account));
    assertFalse(v3.contains("principals[0].account-grants[1]=" + seller));
    assertTrue(v2.contains("principals[4].status=ACTIVE"));
    assertTrue(v3.contains("principals[4].status=DISABLED"));
    assertTrue(v2.contains("principals[2].account-grants[0]=" + account));
    assertTrue(v3.contains("principals[2].account-grants[0]=" + account));
    assertTrue(v3.contains("principals[2].status=ACTIVE"));
    assertTrue(v3.contains("principals[3].account-grants[0]=" + seller));
    for (int index = 0; index < 5; index++) {
      final String needle = "principals[" + index + "].id=";
      assertEquals(baseline.lines().filter(line -> line.contains(needle)).findFirst(),
          v2.lines().filter(line -> line.contains(needle)).findFirst());
      assertEquals(baseline.lines().filter(line -> line.contains(needle)).findFirst(),
          v3.lines().filter(line -> line.contains(needle)).findFirst());
    }
    assertFalse(version2.readerToken().equals(credentials.readerToken()));
    assertFalse(version3.readerToken().equals(version2.readerToken()));
    assertFalse(version2.operationsToken().equals(version3.operationsToken()));
    final var all = new java.util.HashSet<>(credentials.tokens().values());
    for (var generation : java.util.List.of(version2, version3)) {
      final String policy = generation.secret().get("query-service.properties");
      assertEquals(Set.copyOf(KubernetesQueryPermissionMatrix.ALIASES),
          generation.tokens().keySet());
      assertEquals(5, Set.copyOf(generation.tokens().values()).size());
      for (String token : generation.tokens().values()) {
        assertFalse(policy.contains(token));
        assertFalse(generation.toString().contains(token));
        assertTrue(all.add(token), "a credential was reused across policy generations");
      }
      assertFalse(generation.toString().contains("postgres://"));
    }
    assertEquals(15, all.size());
  }
}
