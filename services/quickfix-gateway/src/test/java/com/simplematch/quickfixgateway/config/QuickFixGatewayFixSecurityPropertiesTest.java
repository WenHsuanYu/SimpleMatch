package com.simplematch.quickfixgateway.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.simplematch.quickfixgateway.fix.FixSessionAccess;
import com.simplematch.quickfixgateway.fix.OrderSessionRegistry;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class QuickFixGatewayFixSecurityPropertiesTest {
  @Test
  void bindsSecretCounterpartiesAndWhitelistedTlsVariablesWithoutDisclosingCredentials() {
    final String prefix = "simplematch.quickfix-gateway.fix-security.";
    final var source =
        new MapConfigurationPropertySource(
            Map.of(
                prefix + "counterparties[0].session-id", "FIX.4.4:SIMPLEMATCH->CLIENT",
                prefix + "counterparties[0].username", "trader",
                prefix + "counterparties[0].password", "never-log-this-password",
                prefix + "counterparties[0].account-ids[0]", "0194a8f0-7c77-7b38-9e2d-2a5fdd0f7c13",
                prefix + "key-store-password", "key-password",
                prefix + "trust-store-password", "trust-password"));
    final QuickFixGatewayFixSecurityProperties properties =
        new Binder(source)
            .bind(
                "simplematch.quickfix-gateway.fix-security",
                Bindable.of(QuickFixGatewayFixSecurityProperties.class))
            .get();

    assertThat(properties.counterparties()).hasSize(1);
    assertThat(properties.quickfixVariables())
        .containsOnly(
            Map.entry("FIX_KEY_STORE_PASSWORD", "key-password"),
            Map.entry("FIX_TRUST_STORE_PASSWORD", "trust-password"));
    assertThat(properties.toString())
        .contains("counterpartyCount=1")
        .doesNotContain("key-password", "trust-password");
    assertThat(properties.counterparties().getFirst().toString())
        .contains("accountCount=1")
        .doesNotContain("trader", "never-log-this-password");
    assertThatThrownBy(() -> properties.counterparties().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(
            () ->
                new FixSessionAccess(
                    java.util.List.of(
                        properties.counterparties().getFirst(),
                        properties.counterparties().getFirst()),
                    new OrderSessionRegistry()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("duplicate FIX counterparty session");
  }

  @Test
  void absentSecretsLeaveNoAuthorizedCounterparties() {
    final var properties = new QuickFixGatewayFixSecurityProperties(null, null, null);
    assertThat(properties.counterparties()).isEmpty();
    assertThat(properties.quickfixVariables())
        .containsOnly(
            Map.entry("FIX_KEY_STORE_PASSWORD", ""), Map.entry("FIX_TRUST_STORE_PASSWORD", ""));
  }
}
