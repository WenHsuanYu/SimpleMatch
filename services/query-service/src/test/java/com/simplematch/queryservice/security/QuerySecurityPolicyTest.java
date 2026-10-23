package com.simplematch.queryservice.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.simplematch.queryservice.config.QueryServiceProperties;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.authentication.BadCredentialsException;

class QuerySecurityPolicyTest {
  @Test
  void boundedCredentialOverlapPreservesPrincipalAndExpiresThePreviousCredential() {
    final Instant now = Instant.now();
    final QuerySecurityFixtures fixture =
        new QuerySecurityFixtures()
            .property("credentials[3].expires-at", now.plusSeconds(60).toString())
            .credential("replacement", "operations", true, now.plusSeconds(3600));
    final QuerySecurityPolicy before =
        new QuerySecurityPolicy(fixture.bind(), Clock.fixed(now, ZoneOffset.UTC));
    final QuerySecurityPolicy after =
        new QuerySecurityPolicy(fixture.bind(), Clock.fixed(now.plusSeconds(60), ZoneOffset.UTC));

    assertThat(before.authenticate(fixture.token("operations")).id())
        .isEqualTo(before.authenticate(fixture.token("replacement")).id());
    assertThatThrownBy(() -> after.authenticate(fixture.token("operations")))
        .isInstanceOf(BadCredentialsException.class);
    assertThat(after.authenticate(fixture.token("replacement")).id())
        .isEqualTo(fixture.principalId("operations"));
  }

  @Test
  void authenticationRetainsOneImmutablePolicySnapshot() {
    final QuerySecurityFixtures fixture = new QuerySecurityFixtures();
    final QuerySecurityPolicy original = new QuerySecurityPolicy(fixture.bind(), Clock.systemUTC());
    fixture.property("policy-version", "2").property("principals[3].operations-read", "false");
    final QuerySecurityPolicy changed = new QuerySecurityPolicy(fixture.bind(), Clock.systemUTC());
    final QueryPrincipal principal = original.authenticate(fixture.token("operations"));

    assertThat(principal.operationsRead()).isTrue();
    assertThat(principal.policyVersion()).isEqualTo(1);
    assertThat(changed.authenticate(fixture.token("operations")).operationsRead()).isFalse();
    assertThat(changed.authenticate(fixture.token("operations")).policyVersion()).isEqualTo(2);
    assertThat(changed.authenticate(fixture.token("operations")).id()).isEqualTo(principal.id());
  }

  @Test
  void aRetiredPrincipalCannotBeRecreatedByItsStillValidCredential() {
    final QuerySecurityFixtures fixture =
        new QuerySecurityFixtures().property("principals[4].status", "RETIRED");
    final QuerySecurityPolicy policy = new QuerySecurityPolicy(fixture.bind(), Clock.systemUTC());

    assertThatThrownBy(() -> policy.authenticate(fixture.token("client-d")))
        .isInstanceOf(BadCredentialsException.class);
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"malformed", "Bearer untrusted"})
  void invalidCredentialsNeverProduceAnIdentity(String token) {
    final QuerySecurityPolicy policy =
        new QuerySecurityPolicy(new QuerySecurityFixtures().bind(), Clock.systemUTC());
    assertThatThrownBy(() -> policy.authenticate(token))
        .isInstanceOf(BadCredentialsException.class);
  }

  @Test
  void rebuildConfigurationDiagnosticsDoNotExposeTheOperatorCredential() {
    final String operator = new QuerySecurityFixtures().token("operations");
    assertThat(new QueryServiceProperties.Rebuild(true, operator).toString().contains(operator))
        .isFalse();
  }
}
