package com.simplematch.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

class SimpleMatchConfigEnvironmentPostProcessorTest {
  private final SimpleMatchConfigEnvironmentPostProcessor postProcessor =
      new SimpleMatchConfigEnvironmentPostProcessor();

  @TempDir
  Path tempDir;

  // Verify that the environment post-processor merges JSON, environment variables, and legacy CLI overrides.
  // Scenario: provide JSON, SIMPLEMATCH_* values, and commandLineArgs, then confirm the final binding order.
  @DisplayName("environment post-processor merges JSON and legacy overrides")
  @Test
  void bindsExistingJsonAndLegacyOverrides() throws Exception {
    final Path jsonPath = tempDir.resolve("custom/simplematch.json");
    Files.createDirectories(jsonPath.getParent());
    Files.writeString(
        jsonPath,
        """
        {
          "env": "json",
          "kafka": {
            "brokers": "json-broker:9092"
          },
          "quickfixGateway": {
            "quickfixConfigPath": "config/quickfix/from-json.cfg",
            "walPath": "data/quickfix/wal/from-json.wal",
            "ownerId": "json-owner"
          }
        }
        """);

    final ConfigurableEnvironment environment = new StandardEnvironment();
    final Map<String, Object> systemEnvironment = new HashMap<>();
    systemEnvironment.put("SIMPLEMATCH_CONFIG", jsonPath.toString());
    systemEnvironment.put("SIMPLEMATCH_ENV", "env-override");
    systemEnvironment.put("SIMPLEMATCH_QUICKFIX_GATEWAY_WAL_PATH", "data/quickfix/wal/from-env.wal");
    systemEnvironment.put("SIMPLEMATCH_QUICKFIX_GATEWAY_OWNER_ID", "env-owner");
    environment.getPropertySources().replace(
        StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
        new MapPropertySource(
            StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
        systemEnvironment));
    final Map<String, Object> commandLineArgs = new HashMap<>();
    commandLineArgs.put("quickfix-config", "config/quickfix/from-cli.cfg");
    commandLineArgs.put("wal", "data/quickfix/wal/from-cli.wal");
    environment.getPropertySources().addFirst(
      new MapPropertySource("commandLineArgs", commandLineArgs));

    postProcessor.postProcessEnvironment(environment, new SpringApplication(Object.class));

    final SimpleMatchConfig config = Binder.get(environment)
        .bind("simplematch", Bindable.of(SimpleMatchConfig.class))
      .orElseThrow(() -> new IllegalStateException("simplematch config should bind"));

    assertEquals("env-override", config.getEnv());
    assertEquals("json-broker:9092", config.getKafka().getBrokers());
    assertEquals("config/quickfix/from-cli.cfg", config.getQuickfixGateway().getQuickfixConfigPath());
    assertEquals("data/quickfix/wal/from-cli.wal", config.getQuickfixGateway().getWalPath());
    assertEquals("env-owner", config.getQuickfixGateway().getOwnerId());
  }

  // Verify that the app-config command-line argument takes precedence over SIMPLEMATCH_CONFIG.
  // Scenario: provide both environment and CLI config paths, then confirm the CLI file wins.
  @DisplayName("app-config takes precedence over SIMPLEMATCH_CONFIG")
  @Test
  void appConfigArgumentBeatsSimplematchConfigEnv(@TempDir Path overrideDir) throws Exception {
    final Path envJsonPath = tempDir.resolve("env/simplematch.json");
    final Path cliJsonPath = overrideDir.resolve("cli/simplematch.json");
    Files.createDirectories(envJsonPath.getParent());
    Files.createDirectories(cliJsonPath.getParent());
    Files.writeString(envJsonPath, "{\"env\":\"env-json\"}");
    Files.writeString(cliJsonPath, "{\"env\":\"cli-json\"}");

    final ConfigurableEnvironment environment = new StandardEnvironment();
    final Map<String, Object> systemEnvironment = new HashMap<>();
    systemEnvironment.put("SIMPLEMATCH_CONFIG", envJsonPath.toString());
    environment.getPropertySources().replace(
        StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
        new MapPropertySource(
            StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
        systemEnvironment));
    final Map<String, Object> commandLineArgs = new HashMap<>();
    commandLineArgs.put("app-config", cliJsonPath.toString());
    environment.getPropertySources().addFirst(
      new MapPropertySource("commandLineArgs", commandLineArgs));

    postProcessor.postProcessEnvironment(environment, new SpringApplication(Object.class));

    final SimpleMatchConfig config = Binder.get(environment)
        .bind("simplematch", Bindable.of(SimpleMatchConfig.class))
      .orElseThrow(() -> new IllegalStateException("simplematch config should bind"));

    assertEquals("cli-json", config.getEnv());
  }

  // Verify that legacy Spring property names are mapped to the new quickfix-gateway namespace.
  // Scenario: provide only legacy fix-gateway properties and confirm the new keys resolve to the same values.
  @DisplayName("legacy Spring property names map to the new quickfix-gateway namespace")
  @Test
  void oldSpringPropertyNamesAreAliasedToNewQuickfixGatewayNamespace() {
    final ConfigurableEnvironment environment = new StandardEnvironment();
    final Map<String, Object> legacyProperties = new HashMap<>();
    legacyProperties.put("simplematch.fix-gateway.acceptor-enabled", "false");
    legacyProperties.put("simplematch.fix-gateway.data-plane-enabled", "false");
    legacyProperties.put("simplematch.fix-gateway.replay-enabled", "false");

    environment.getPropertySources().addFirst(
        new MapPropertySource("legacyProperties", legacyProperties));

    postProcessor.postProcessEnvironment(environment, new SpringApplication(Object.class));

    assertEquals("false", environment.getProperty("simplematch.quickfix-gateway.acceptor-enabled"));
    assertEquals("false", environment.getProperty("simplematch.quickfix-gateway.data-plane-enabled"));
    assertEquals("false", environment.getProperty("simplematch.quickfix-gateway.replay-enabled"));
  }
}