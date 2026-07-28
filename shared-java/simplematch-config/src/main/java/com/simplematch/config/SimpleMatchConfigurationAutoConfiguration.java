package com.simplematch.config;

import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * Installs shared typed configuration and validates the resolved Spring Environment.
 */
@AutoConfiguration
@EnableConfigurationProperties(PlatformProperties.class)
public final class SimpleMatchConfigurationAutoConfiguration {
    @Bean
    EnvironmentConfigurationValidator environmentConfigurationValidator() {
        return new EnvironmentConfigurationValidator();
    }

    @Bean
    SmartInitializingSingleton simpleMatchConfigurationStartupValidation(
            EnvironmentConfigurationValidator validator,
            ConfigurableEnvironment environment,
            PlatformProperties properties) {
        return () -> validator.validate(environment, properties);
    }
}
