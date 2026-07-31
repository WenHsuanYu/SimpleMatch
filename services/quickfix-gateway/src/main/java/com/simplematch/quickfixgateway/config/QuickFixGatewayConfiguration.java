package com.simplematch.quickfixgateway.config;

import com.simplematch.config.PlatformProperties;
import com.simplematch.quickfixgateway.fix.FixSessionMessageSender;
import com.simplematch.quickfixgateway.fix.OrderSessionRegistry;
import com.simplematch.quickfixgateway.fix.QuickFixSessionMessageSender;
import com.simplematch.quickfixgateway.wal.WalAppender;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;

/** Wires the QuickFIX gateway's runtime, messaging, and recovery components. */
@Configuration
@EnableKafka
@EnableConfigurationProperties(QuickFixGatewayProperties.class)
public class QuickFixGatewayConfiguration {
  @Bean
  SmartInitializingSingleton quickFixGatewayPropertiesValidation(
      QuickFixGatewayProperties gatewayProperties) {
    return () -> QuickFixGatewayPropertiesValidator.validate(gatewayProperties);
  }

  @Bean
  QuickFixGatewayRuntime quickFixGatewayRuntime(
      PlatformProperties platformProperties, QuickFixGatewayProperties gatewayProperties)
      throws IOException {
    final Path quickfixConfigPath = resolve(gatewayProperties.quickfixConfigPath());
    if (!Files.exists(quickfixConfigPath)) {
      throw new IllegalStateException("QuickFIX/J config not found: " + quickfixConfigPath);
    }

    final Path walPath = resolve(gatewayProperties.walPath());
    final Path walParent = walPath.getParent();
    if (walParent != null) {
      Files.createDirectories(walParent);
    }

    return new QuickFixGatewayRuntime(
        platformProperties.environment(), quickfixConfigPath, walPath, gatewayProperties.ownerId());
  }

  @Bean
  Clock quickFixGatewayClock() {
    return Clock.systemUTC();
  }

  @Bean
  WalAppender walAppender(QuickFixGatewayRuntime runtime) {
    return new WalAppender(runtime.walPath(), StandardCharsets.UTF_8);
  }

  @Bean
  FixSessionMessageSender fixSessionMessageSender() {
    return new QuickFixSessionMessageSender();
  }

  @Bean
  OrderSessionRegistry orderSessionRegistry() {
    return new OrderSessionRegistry();
  }

  private Path resolve(String rawPath) {
    final Path path = Path.of(rawPath);
    if (path.isAbsolute()) {
      return path.normalize();
    }

    Path current = Path.of("").toAbsolutePath().normalize();
    while (current != null) {
      if (Files.exists(current.resolve("settings.gradle.kts"))
          || Files.exists(current.resolve(".git"))) {
        return current.resolve(path).normalize();
      }
      current = current.getParent();
    }

    return Path.of("").toAbsolutePath().resolve(path).normalize();
  }
}
