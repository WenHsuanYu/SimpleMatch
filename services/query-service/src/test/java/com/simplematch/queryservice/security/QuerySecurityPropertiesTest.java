package com.simplematch.queryservice.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

class QuerySecurityPropertiesTest {
  private static final String PREFIX = "simplematch.query-service.security";

  @Test
  void bindsTheCompleteApprovedPolicyThroughSpringEnvironment() {
    final QuerySecurityProperties policy = bind(validProperties());

    assertThat(policy.schemaVersion()).isEqualTo(1);
    assertThat(policy.policyVersion()).isEqualTo(7);
    assertThat(policy.principals()).hasSize(1);
    assertThat(policy.principals().getFirst().status())
        .isEqualTo(QuerySecurityProperties.PrincipalStatus.ACTIVE);
    assertThat(policy.principals().getFirst().operationsRead()).isTrue();
    assertThat(policy.principals().getFirst().accountGrants()).hasSize(1);
    assertThat(policy.credentials().getFirst().enabled()).isTrue();
    assertThat(policy.credentials().getFirst().principalId())
        .isEqualTo(policy.principals().getFirst().id());
  }

  @Test
  void rejectsUnsupportedPolicySchemaBeforeActivation() {
    final Map<String, Object> properties = validProperties();
    properties.put(PREFIX + ".schema-version", "2");

    assertThatThrownBy(() -> bind(properties))
        .hasRootCauseInstanceOf(IllegalArgumentException.class)
        .hasRootCauseMessage("unsupported Query security policy schema");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "schema-version",
        "policy-version",
        "principals[0].id",
        "principals[0].status",
        "credentials[0].id",
        "credentials[0].principal-id",
        "credentials[0].sha256",
        "credentials[0].expires-at"
      })
  void rejectsMissingMandatoryConfiguration(String property) {
    final Map<String, Object> properties = validProperties();
    properties.remove(PREFIX + "." + property);

    assertThatThrownBy(() -> bind(properties))
        .hasRootCauseInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest
  @ValueSource(strings = {" ", " leading", "trailing ", "embedded space", "control\nidentity"})
  void rejectsAmbiguousPrincipalIdentity(String identity) {
    final Map<String, Object> properties = validProperties();
    properties.put(PREFIX + ".principals[0].id", identity);
    properties.put(PREFIX + ".credentials[0].principal-id", identity);

    assertThatThrownBy(() -> bind(properties))
        .hasRootCauseInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest
  @ValueSource(strings = {"not-a-uuid", "1-1-1-1-1", "AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA"})
  void rejectsNoncanonicalAccountGrants(String account) {
    final Map<String, Object> properties = validProperties();
    properties.put(PREFIX + ".principals[0].account-grants[0]", account);

    assertThatThrownBy(() -> bind(properties))
        .hasRootCauseInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest
  @ValueSource(strings = {"DISABLED", "RETIRED"})
  void rejectsAccountGrantsOnInactivePrincipals(String status) {
    final Map<String, Object> properties = validProperties();
    properties.put(PREFIX + ".principals[0].status", status);

    assertThatThrownBy(() -> bind(properties))
        .hasRootCauseInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest
  @ValueSource(strings = {"principals", "credentials"})
  void rejectsMissingRegistrationLists(String collection) {
    final Map<String, Object> properties = validProperties();
    properties.keySet().removeIf(key -> key.startsWith(PREFIX + "." + collection + "["));

    assertThatThrownBy(() -> bind(properties))
        .hasRootCauseInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest
  @ValueSource(strings = {"principal", "credential", "verifier", "account"})
  void rejectsDuplicateIdentityMappingsAndGrants(String duplicate) {
    final Map<String, Object> properties = validProperties();
    if ("principal".equals(duplicate)) {
      copyRegistration(properties, "principals");
    } else if ("account".equals(duplicate)) {
      properties.put(
          PREFIX + ".principals[0].account-grants[1]",
          properties.get(PREFIX + ".principals[0].account-grants[0]"));
    } else {
      copyRegistration(properties, "credentials");
      if ("verifier".equals(duplicate)) {
        properties.put(PREFIX + ".credentials[1].id", UUID.randomUUID().toString());
      }
    }

    assertThatThrownBy(() -> bind(properties))
        .hasRootCauseInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsUnknownPrincipalReferencesAndInvalidVerifiersWithoutEchoingValues() {
    final Map<String, Object> properties = validProperties();
    properties.put(PREFIX + ".credentials[0].principal-id", UUID.randomUUID().toString());
    assertThatThrownBy(() -> bind(properties))
        .hasRootCauseMessage("Query credential references an unknown principal");

    final Map<String, Object> invalidVerifier = validProperties();
    invalidVerifier.put(PREFIX + ".credentials[0].sha256", "malformed-confidential-material");
    assertThatThrownBy(() -> bind(invalidVerifier))
        .hasRootCauseMessage("Query credential requires a lowercase SHA-256 verifier");
  }

  @ParameterizedTest
  @ValueSource(strings = {"DISABLED", "RETIRED"})
  void permitsInactiveAndExpiredRecordsWithoutRestoringGrants(String status) {
    final Map<String, Object> properties = validProperties();
    properties.put(PREFIX + ".principals[0].status", status);
    properties.remove(PREFIX + ".principals[0].account-grants[0]");
    properties.put(PREFIX + ".credentials[0].enabled", "false");
    properties.put(PREFIX + ".credentials[0].expires-at", Instant.EPOCH.toString());

    final QuerySecurityProperties policy = bind(properties);

    assertThat(policy.principals().getFirst().accountGrants()).isEmpty();
    assertThat(policy.credentials().getFirst().enabled()).isFalse();
    assertThat(policy.credentials().getFirst().expiresAt()).isEqualTo(Instant.EPOCH);
  }

  @Test
  void copiesCollectionsAndRedactsAllSensitiveDiagnostics() {
    final QuerySecurityProperties bound = bind(validProperties());
    final List<String> grants = new ArrayList<>(bound.principals().getFirst().accountGrants());
    final QuerySecurityProperties.PrincipalRegistration original = bound.principals().getFirst();
    final QuerySecurityProperties.PrincipalRegistration principal =
        new QuerySecurityProperties.PrincipalRegistration(
            original.id(), original.status(), grants, true);
    final List<QuerySecurityProperties.PrincipalRegistration> principals =
        new ArrayList<>(List.of(principal));
    final List<QuerySecurityProperties.CredentialRegistration> credentials =
        new ArrayList<>(bound.credentials());
    final QuerySecurityProperties policy =
        new QuerySecurityProperties(1, 7, principals, credentials);
    grants.clear();
    principals.clear();
    credentials.clear();

    assertThat(policy.principals()).hasSize(1);
    assertThat(policy.credentials()).hasSize(1);
    assertThat(policy.principals().getFirst().accountGrants()).hasSize(1);
    assertThatThrownBy(() -> policy.principals().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> policy.credentials().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> principal.accountGrants().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    final String diagnostic = policy + " " + principal + " " + policy.credentials().getFirst();
    assertThat(diagnostic.contains(policy.credentials().getFirst().sha256())).isFalse();
    assertThat(diagnostic.contains(principal.accountGrants().getFirst())).isFalse();
    assertThat(diagnostic.contains(principal.id())).isFalse();
  }

  private void copyRegistration(Map<String, Object> properties, String collection) {
    final String source = PREFIX + "." + collection + "[0].";
    final String target = PREFIX + "." + collection + "[1].";
    new LinkedHashMap<>(properties)
        .forEach(
            (key, value) -> {
              if (key.startsWith(source)) {
                properties.put(target + key.substring(source.length()), value);
              }
            });
  }

  private QuerySecurityProperties bind(Map<String, Object> properties) {
    final StandardEnvironment environment = new StandardEnvironment();
    environment
        .getPropertySources()
        .addFirst(new MapPropertySource("query-security-test", properties));
    return Binder.get(environment)
        .bind(PREFIX, Bindable.of(QuerySecurityProperties.class))
        .orElseThrow(() -> new IllegalStateException("test policy should bind"));
  }

  private Map<String, Object> validProperties() {
    final String principal = UUID.randomUUID().toString();
    final byte[] digest = new byte[32];
    new SecureRandom().nextBytes(digest);
    final Map<String, Object> values = new LinkedHashMap<>();
    values.put(PREFIX + ".schema-version", "1");
    values.put(PREFIX + ".policy-version", "7");
    values.put(PREFIX + ".principals[0].id", principal);
    values.put(PREFIX + ".principals[0].status", "ACTIVE");
    values.put(PREFIX + ".principals[0].operations-read", "true");
    values.put(PREFIX + ".principals[0].account-grants[0]", UUID.randomUUID().toString());
    values.put(PREFIX + ".credentials[0].id", UUID.randomUUID().toString());
    values.put(PREFIX + ".credentials[0].principal-id", principal);
    values.put(PREFIX + ".credentials[0].sha256", HexFormat.of().formatHex(digest));
    values.put(PREFIX + ".credentials[0].enabled", "true");
    values.put(PREFIX + ".credentials[0].expires-at", Instant.now().plusSeconds(3600).toString());
    return values;
  }
}
