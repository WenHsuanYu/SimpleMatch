package com.simplematch.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SimpleMatchConfigLoaderTest {
  private final SimpleMatchConfigLoader loader = new SimpleMatchConfigLoader();

  @TempDir
  Path tempDir;

  // Verify that explicit overrides beat environment variables and JSON settings.
  // Scenario: provide JSON, environment variables, and CLI overrides together, then confirm the explicit override wins.
  @DisplayName("explicit overrides beat environment variables and JSON settings")
  @Test
  void explicitOverridesBeatEnvironmentAndJson() throws IOException {
    final Path jsonConfig = tempDir.resolve("simplematch.json");
    Files.writeString(jsonConfig, """
        {
          "env": "json",
          "quickfixGateway": {
            "quickfixConfigPath": "json/fix.cfg",
            "walPath": "json/inbound.wal",
            "ownerId": "json-owner"
          }
        }
        """);

    final Map<String, String> environment = new HashMap<>();
    environment.put("SIMPLEMATCH_CONFIG", jsonConfig.toString());
    environment.put("SIMPLEMATCH_ENV", "env");
    environment.put("SIMPLEMATCH_QUICKFIX_GATEWAY_QUICKFIX_CONFIG", "env/fix.cfg");
    environment.put("SIMPLEMATCH_QUICKFIX_GATEWAY_WAL_PATH", "env/inbound.wal");
    environment.put("SIMPLEMATCH_QUICKFIX_GATEWAY_OWNER_ID", "env-owner");

    final LoadedSimpleMatchConfig loaded = loader.load(new SimpleMatchConfigLoadRequest(
        tempDir,
        environment,
        new SimpleMatchConfigOverrides(null, "cli", "cli/fix.cfg", "cli/inbound.wal")));

    assertEquals("cli", loaded.config().getEnv());
    assertEquals("cli/fix.cfg", loaded.config().getQuickfixGateway().getQuickfixConfigPath());
    assertEquals("cli/inbound.wal", loaded.config().getQuickfixGateway().getWalPath());
    assertTrue(loaded.appConfigPath().isPresent());
    assertEquals(jsonConfig, loaded.appConfigPath().orElseThrow());
  }

  // Verify that an explicitly specified app config path takes precedence over the environment config path.
  // Scenario: provide both SIMPLEMATCH_CONFIG and overrides.appConfigPath, then confirm the override file is loaded.
  @DisplayName("explicit app config path takes precedence over the environment config path")
  @Test
  void explicitAppConfigPathBeatsEnvironmentConfigPath() throws IOException {
    final Path configFromEnv = tempDir.resolve("from-env.json");
    final Path configFromOverride = tempDir.resolve("from-override.json");

    Files.writeString(configFromEnv, "{\"env\":\"env-json\"}");
    Files.writeString(configFromOverride, "{\"env\":\"override-json\"}");

    final Map<String, String> environment = Map.of("SIMPLEMATCH_CONFIG", configFromEnv.toString());

    final LoadedSimpleMatchConfig loaded = loader.load(new SimpleMatchConfigLoadRequest(
        tempDir,
        environment,
        new SimpleMatchConfigOverrides(configFromOverride.toString(), null, null, null)));

    assertEquals("override-json", loaded.config().getEnv());
    assertEquals(configFromOverride, loaded.appConfigPath().orElseThrow());
  }

  // Verify that environment variables override the matching values in JSON when no explicit overrides are present.
  // Scenario: provide JSON and environment variables, then confirm env and quickfix paths use the environment values.
  @DisplayName("environment variables take precedence over JSON when no explicit overrides are present")
  @Test
  void environmentOverridesJsonWhenNoExplicitOverrides() throws IOException {
    final Path jsonConfig = tempDir.resolve("from-env.json");
    Files.writeString(jsonConfig, """
        {
          "env": "json",
          "quickfixGateway": {
            "quickfixConfigPath": "json/fix.cfg",
            "walPath": "json/inbound.wal",
            "ownerId": "json-owner"
          }
        }
        """);

    final Map<String, String> environment = new HashMap<>();
    environment.put("SIMPLEMATCH_CONFIG", jsonConfig.toString());
    environment.put("SIMPLEMATCH_ENV", "env");
    environment.put("SIMPLEMATCH_QUICKFIX_GATEWAY_QUICKFIX_CONFIG", "env/fix.cfg");
    environment.put("SIMPLEMATCH_QUICKFIX_GATEWAY_WAL_PATH", "env/inbound.wal");
    environment.put("SIMPLEMATCH_QUICKFIX_GATEWAY_OWNER_ID", "env-owner");

    final LoadedSimpleMatchConfig loaded = loader.load(new SimpleMatchConfigLoadRequest(
        tempDir,
        environment,
        new SimpleMatchConfigOverrides(null, null, null, null)));

    assertEquals("env", loaded.config().getEnv());
    assertEquals("env/fix.cfg", loaded.config().getQuickfixGateway().getQuickfixConfigPath());
    assertEquals("env/inbound.wal", loaded.config().getQuickfixGateway().getWalPath());
    assertEquals("env-owner", loaded.config().getQuickfixGateway().getOwnerId());
    assertEquals(jsonConfig, loaded.appConfigPath().orElseThrow());
  }

  // Verify that the loader falls back to the default config/simplematch.json path when no external path is provided.
  // Scenario: create the default config file in tempDir and confirm the loader finds and reads it automatically.
  @DisplayName("loads the default config file when no path is specified")
  @Test
  void defaultConfigPathLoadsWhenNoOverrideOrEnvironmentPathExists() throws IOException {
    final Path defaultConfig = tempDir.resolve("config/simplematch.json");
    Files.createDirectories(defaultConfig.getParent());
    Files.writeString(defaultConfig, """
        {
          "env": "default-json",
          "quickfixGateway": {
            "quickfixConfigPath": "default/fix.cfg",
            "walPath": "default/inbound.wal"
          }
        }
        """);

    final LoadedSimpleMatchConfig loaded = loader.load(new SimpleMatchConfigLoadRequest(
        tempDir,
        Map.of(),
        new SimpleMatchConfigOverrides(null, null, null, null)));

    assertEquals("default-json", loaded.config().getEnv());
    assertEquals("default/fix.cfg", loaded.config().getQuickfixGateway().getQuickfixConfigPath());
    assertEquals("default/inbound.wal", loaded.config().getQuickfixGateway().getWalPath());
    assertEquals(defaultConfig, loaded.appConfigPath().orElseThrow());
  }

  // Verify that the system keeps its built-in defaults when no JSON, environment variables, or overrides are provided.
  // Scenario: provide an empty environment and empty overrides, then confirm the config path and env come from defaults.
  @DisplayName("keeps the built-in defaults when no external config is provided")
  @Test
  void builtInDefaultsRemainWhenNoJsonEnvironmentOrOverridesExist() {
    final LoadedSimpleMatchConfig loaded = loader.load(new SimpleMatchConfigLoadRequest(
        tempDir,
        Map.of(),
        new SimpleMatchConfigOverrides(null, null, null, null)));

    assertTrue(loaded.appConfigPath().isEmpty());
    assertEquals("dev", loaded.config().getEnv());
    assertEquals("config/quickfix/acceptor.cfg", loaded.config().getQuickfixGateway().getQuickfixConfigPath());
    assertEquals("data/quickfix/wal/inbound.wal", loaded.config().getQuickfixGateway().getWalPath());
    assertEquals("quickfix-gateway-0", loaded.config().getQuickfixGateway().getOwnerId());
  }

  // Verify that legacy fixGateway JSON and environment variable aliases still map to the new quickfixGateway settings.
  // Scenario: load settings using the legacy fixGateway field name and confirm the new namespace still resolves the values.
  @DisplayName("legacy FIX Gateway aliases still map to the new settings fields")
  @Test
  void legacyFixGatewayAliasesStillLoad() throws IOException {
    final Path jsonConfig = tempDir.resolve("legacy-simplematch.json");
    Files.writeString(jsonConfig, """
        {
          "env": "json",
          "fixGateway": {
            "quickfixConfigPath": "json/fix.cfg",
            "walPath": "json/inbound.wal"
          }
        }
        """);

    final Map<String, String> environment = new HashMap<>();
    environment.put("SIMPLEMATCH_CONFIG", jsonConfig.toString());
    environment.put("SIMPLEMATCH_FIX_QUICKFIX_CONFIG", "env/fix.cfg");
    environment.put("SIMPLEMATCH_FIX_WAL_PATH", "env/inbound.wal");

    final LoadedSimpleMatchConfig loaded = loader.load(new SimpleMatchConfigLoadRequest(
        tempDir,
        environment,
        new SimpleMatchConfigOverrides(null, null, null, null)));

    assertEquals("env/fix.cfg", loaded.config().getQuickfixGateway().getQuickfixConfigPath());
    assertEquals("env/inbound.wal", loaded.config().getQuickfixGateway().getWalPath());
  }
}