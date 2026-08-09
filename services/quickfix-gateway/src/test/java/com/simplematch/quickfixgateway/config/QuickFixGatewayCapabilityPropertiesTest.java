package com.simplematch.quickfixgateway.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

class QuickFixGatewayCapabilityPropertiesTest {
  @Test
  void bindsEachCapabilityFromTheCanonicalSpringPropertyNamespace() {
    final StandardEnvironment environment = new StandardEnvironment();
    environment
        .getPropertySources()
        .addFirst(
            new MapPropertySource(
                "test",
                java.util.Map.of(
                    "simplematch.quickfix-gateway.quickfix-config-path", "custom/acceptor.cfg",
                    "simplematch.quickfix-gateway.owner-id", "quickfix-gateway-1",
                    "simplematch.quickfix-gateway.risk-client.deadline-millis", "2300")));

    final QuickFixGatewayFileProperties fileProperties =
        Binder.get(environment)
            .bind(
                "simplematch.quickfix-gateway",
                Bindable.of(QuickFixGatewayFileProperties.class))
            .orElseThrow(() -> new IllegalStateException("file properties should bind"));

    final QuickFixGatewayRuntimeProperties runtimeProperties =
        Binder.get(environment)
            .bind(
                "simplematch.quickfix-gateway",
                Bindable.of(QuickFixGatewayRuntimeProperties.class))
            .orElseThrow(() -> new IllegalStateException("runtime properties should bind"));

    final QuickFixGatewayRiskClientProperties riskClientProperties =
        Binder.get(environment)
            .bind(
                "simplematch.quickfix-gateway.risk-client",
                Bindable.of(QuickFixGatewayRiskClientProperties.class))
            .orElseThrow(() -> new IllegalStateException("risk properties should bind"));

    assertThat(runtimeProperties.ownerId()).isEqualTo("quickfix-gateway-1");
    assertThat(riskClientProperties.deadlineMillis()).isEqualTo(2300);
    assertThat(fileProperties.quickfixConfigPath()).isEqualTo("custom/acceptor.cfg");
    assertThat(fileProperties.walPath()).isEqualTo("data/quickfix/wal/inbound.wal");
    assertThat(runtimeProperties.acceptorEnabled()).isTrue();
    assertThat(runtimeProperties.dataPlaneEnabled()).isTrue();
    assertThat(runtimeProperties.replayEnabled()).isTrue();
  }

  @Test
  void rejectsInvalidRiskClientPolicy() {
    final QuickFixGatewayRiskClientProperties properties =
        new QuickFixGatewayRiskClientProperties(-1, null, null);

    assertThatThrownBy(() -> QuickFixGatewayRiskClientPropertiesValidator.validate(properties))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("risk-client");
  }

  @Test
  void keepsEachCapabilityConstructorSmall() {
    assertThat(QuickFixGatewayFileProperties.class.getDeclaredConstructors())
        .hasSize(1)
        .allSatisfy(
            constructor ->
                assertThat(constructor.getParameterCount()).isLessThanOrEqualTo(6));
    assertThat(QuickFixGatewayRuntimeProperties.class.getDeclaredConstructors())
        .hasSize(1)
        .allSatisfy(
            constructor ->
                assertThat(constructor.getParameterCount()).isLessThanOrEqualTo(6));
    assertThat(QuickFixGatewayRiskClientProperties.class.getDeclaredConstructors())
        .hasSize(1)
        .allSatisfy(
            constructor ->
                assertThat(constructor.getParameterCount()).isLessThanOrEqualTo(6));
  }
}
