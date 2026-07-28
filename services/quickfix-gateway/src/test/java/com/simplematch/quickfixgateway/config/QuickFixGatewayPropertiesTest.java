package com.simplematch.quickfixgateway.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QuickFixGatewayPropertiesTest {
    @Test
    void bindsGatewaySettingsFromTheCanonicalSpringPropertyNamespace() {
        final StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test", java.util.Map.of(
                "simplematch.quickfix-gateway.owner-id", "quickfix-gateway-1",
                "simplematch.quickfix-gateway.risk-client.deadline-millis", "2300")));

        final QuickFixGatewayProperties properties = Binder.get(environment)
                .bind("simplematch.quickfix-gateway", Bindable.of(QuickFixGatewayProperties.class))
                .orElseThrow(() -> new IllegalStateException("gateway properties should bind"));

        assertThat(properties.ownerId()).isEqualTo("quickfix-gateway-1");
        assertThat(properties.riskClient().deadlineMillis()).isEqualTo(2300);
        assertThat(properties.quickfixConfigPath()).isEqualTo("config/quickfix/acceptor.cfg");
    }

    @Test
    void rejectsInvalidRiskClientPolicy() {
        final QuickFixGatewayProperties properties = new QuickFixGatewayProperties(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                new QuickFixGatewayProperties.RiskClientProperties(-1, null, null));

        assertThatThrownBy(() -> QuickFixGatewayPropertiesValidator.validate(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("risk-client");
    }
}
