package com.simplematch.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;

public final class SimpleMatchConfigLoader {
  private static final String DEFAULT_CONFIG_PATH = "config/simplematch.json";
  private static final Logger logger = Logger.getLogger(SimpleMatchConfigLoader.class.getName());

  private final ObjectMapper objectMapper;

  public SimpleMatchConfigLoader() {
    this(new ObjectMapper());
  }

  SimpleMatchConfigLoader(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  public LoadedSimpleMatchConfig load(SimpleMatchConfigLoadRequest request) {
    final Path workingDirectory = request.workingDirectory();
    final Map<String, String> environment = request.environment();
    final SimpleMatchConfigOverrides overrides = request.overrides();

    final SimpleMatchConfig config = new SimpleMatchConfig();
    final Optional<Path> appConfigPath = resolveConfigPath(workingDirectory, environment, overrides.appConfigPath());
    appConfigPath.ifPresent(path -> applyJson(config, path));
    applyEnvironment(config, environment);
    applyOverrides(config, overrides);
    return new LoadedSimpleMatchConfig(config, appConfigPath);
  }

  private Optional<Path> resolveConfigPath(
      Path workingDirectory,
      Map<String, String> environment,
      String overrideAppConfigPath) {
    if (overrideAppConfigPath != null && !overrideAppConfigPath.isBlank()) {
      return Optional.of(resolvePath(workingDirectory, overrideAppConfigPath));
    }

    final String envConfigPath = environment.get("SIMPLEMATCH_CONFIG");
    if (envConfigPath != null && !envConfigPath.isBlank()) {
      return Optional.of(resolvePath(workingDirectory, envConfigPath));
    }

    final Path defaultPath = workingDirectory.resolve(DEFAULT_CONFIG_PATH).normalize();
    if (Files.exists(defaultPath)) {
      return Optional.of(defaultPath);
    }

    return Optional.empty();
  }

  private Path resolvePath(Path workingDirectory, String rawPath) {
    final Path path = Path.of(rawPath);
    if (path.isAbsolute()) {
      return path.normalize();
    }
    return workingDirectory.resolve(path).normalize();
  }

  private void applyJson(SimpleMatchConfig config, Path configPath) {
    try {
      warnIfLegacyJsonKeyPresent(configPath);
      objectMapper.readerForUpdating(config).readValue(configPath.toFile());
    } catch (IOException e) {
      throw new IllegalStateException("failed to load config file: " + configPath, e);
    }
  }

  private void applyEnvironment(SimpleMatchConfig config, Map<String, String> environment) {
    applyString(environment.get("SIMPLEMATCH_ENV"), config::setEnv);
    warnIfLegacyEnvVarUsed(environment, "SIMPLEMATCH_FIX_QUICKFIX_CONFIG", "SIMPLEMATCH_QUICKFIX_GATEWAY_QUICKFIX_CONFIG");
    warnIfLegacyEnvVarUsed(environment, "SIMPLEMATCH_FIX_WAL_PATH", "SIMPLEMATCH_QUICKFIX_GATEWAY_WAL_PATH");
    applyString(
        firstNonBlank(
            environment.get("SIMPLEMATCH_QUICKFIX_GATEWAY_QUICKFIX_CONFIG"),
            environment.get("SIMPLEMATCH_FIX_QUICKFIX_CONFIG")),
        config.getQuickfixGateway()::setQuickfixConfigPath);
    applyString(
        firstNonBlank(
            environment.get("SIMPLEMATCH_QUICKFIX_GATEWAY_WAL_PATH"),
            environment.get("SIMPLEMATCH_FIX_WAL_PATH")),
        config.getQuickfixGateway()::setWalPath);
  }

  private void applyOverrides(SimpleMatchConfig config, SimpleMatchConfigOverrides overrides) {
    applyString(overrides.env(), config::setEnv);
    applyString(overrides.quickfixConfigPath(), config.getQuickfixGateway()::setQuickfixConfigPath);
    applyString(overrides.walPath(), config.getQuickfixGateway()::setWalPath);
  }

  private void applyString(String value, java.util.function.Consumer<String> setter) {
    if (value != null && !value.isBlank()) {
      setter.accept(value);
    }
  }

  private String firstNonBlank(String... values) {
    for (String value : values) {
      if (value != null && !value.isBlank()) {
        return value;
      }
    }
    return null;
  }

  private void warnIfLegacyEnvVarUsed(Map<String, String> environment, String deprecatedKey, String replacementKey) {
    final String value = environment.get(deprecatedKey);
    if (value != null && !value.isBlank() && firstNonBlank(environment.get(replacementKey)) == null) {
      logger.warning(() -> "Deprecated environment variable '" + deprecatedKey + "' is in use; prefer '"
          + replacementKey + "'.");
    }
  }

  private void warnIfLegacyJsonKeyPresent(Path configPath) throws IOException {
    final Map<String, Object> raw = objectMapper.readValue(configPath.toFile(), new com.fasterxml.jackson.core.type.TypeReference<>() {});
    if (raw.containsKey("fixGateway")) {
      logger.warning(() -> "Deprecated config key 'fixGateway' detected in " + configPath
          + "; prefer 'quickfixGateway'.");
    }
  }
}