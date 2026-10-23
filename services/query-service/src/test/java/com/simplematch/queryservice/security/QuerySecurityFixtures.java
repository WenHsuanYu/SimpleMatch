package com.simplematch.queryservice.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.test.context.DynamicPropertyRegistry;

/** Generates private synthetic credentials and Spring properties before a test context starts. */
final class QuerySecurityFixtures {
  private final Map<String, String> properties = new LinkedHashMap<>();
  private final Map<String, String> tokens = new LinkedHashMap<>();
  private final Map<String, String> principalIds = new LinkedHashMap<>();

  QuerySecurityFixtures() {
    final String x = UUID.randomUUID().toString();
    final String y = UUID.randomUUID().toString();
    properties.put("schema-version", "1");
    properties.put("policy-version", "1");
    final String[] aliases = {"client-a", "client-b", "client-c", "operations", "client-d"};
    for (int i = 0; i < aliases.length; i++) {
      final String alias = aliases[i];
      final String id = UUID.randomUUID().toString();
      principalIds.put(alias, id);
      properties.put("principals[" + i + "].id", id);
      properties.put("principals[" + i + "].status", "ACTIVE");
      properties.put("principals[" + i + "].operations-read", Boolean.toString(i == 3));
      credential(alias, i, id, true, Instant.now().plusSeconds(3600));
    }
    properties.put("principals[0].account-grants[0]", x);
    properties.put("principals[1].account-grants[0]", y);
    properties.put("principals[2].account-grants[0]", x);
    properties.put("principals[2].account-grants[1]", y);
  }

  void register(DynamicPropertyRegistry registry) {
    properties.forEach((key, value) -> registry.add(prefix(key), () -> value));
  }

  QuerySecurityFixtures property(String key, String value) {
    properties.put(key, value);
    return this;
  }

  QuerySecurityFixtures credential(
      String alias, String principalAlias, boolean enabled, Instant expiry) {
    credential(alias, tokens.size(), principalIds.get(principalAlias), enabled, expiry);
    return this;
  }

  String principalId(String alias) {
    return principalIds.get(alias);
  }

  QuerySecurityProperties bind() {
    final Map<String, Object> source = new LinkedHashMap<>();
    properties.forEach((key, value) -> source.put(prefix(key), value));
    final StandardEnvironment environment = new StandardEnvironment();
    environment.getPropertySources().addFirst(new MapPropertySource("controlled-fixture", source));
    return Binder.get(environment)
        .bind("simplematch.query-service.security", Bindable.of(QuerySecurityProperties.class))
        .get();
  }

  String token(String alias) {
    return tokens.get(alias);
  }

  private void credential(
      String alias, int index, String principalId, boolean enabled, Instant expiry) {
    final byte[] random = new byte[32];
    new SecureRandom().nextBytes(random);
    final String token = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
    tokens.put(alias, token);
    final String prefix = "credentials[" + index + "].";
    properties.put(prefix + "id", UUID.randomUUID().toString());
    properties.put(prefix + "principal-id", principalId);
    properties.put(prefix + "enabled", Boolean.toString(enabled));
    properties.put(prefix + "expires-at", expiry.toString());
    try {
      properties.put(
          prefix + "sha256",
          HexFormat.of()
              .formatHex(
                  MessageDigest.getInstance("SHA-256")
                      .digest(token.getBytes(StandardCharsets.US_ASCII))));
    } catch (NoSuchAlgorithmException failure) {
      throw new IllegalStateException("SHA-256 is required by the test JVM", failure);
    }
  }

  private String prefix(String key) {
    return "simplematch.query-service.security." + key;
  }
}
