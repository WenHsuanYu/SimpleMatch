package com.simplematch.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.lang.Nullable;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;

public final class SimpleMatchConfigEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {
  private static final String JSON_SOURCE_NAME = "simpleMatchJsonConfig";
  private static final String ALIAS_SOURCE_NAME = "simpleMatchLegacyAliases";
  private static final String DEFAULT_CONFIG_PATH = "config/simplematch.json";
  private static final Logger logger = Logger.getLogger(SimpleMatchConfigEnvironmentPostProcessor.class.getName());

  private final ObjectMapper objectMapper = new ObjectMapper();

  private void warnIfDeprecatedPropertyPresent(
      ConfigurableEnvironment environment,
      String deprecatedProperty,
      String replacementProperty) {
    if (deprecatedProperty != null && environment.containsProperty(deprecatedProperty)) {
      warnIfPresent(environment, deprecatedProperty, replacementProperty);
    }
  }

  @Override
  public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
    warnIfDeprecatedPropertyPresent(environment, "simplematch.fix-gateway.quickfix-config-path",
      "simplematch.quickfix-gateway.quickfix-config-path");
    warnIfDeprecatedPropertyPresent(environment, "simplematch.fix-gateway.wal-path",
      "simplematch.quickfix-gateway.wal-path");
    warnIfDeprecatedPropertyPresent(environment, "simplematch.fix-gateway.acceptor-enabled",
      "simplematch.quickfix-gateway.acceptor-enabled");
    warnIfDeprecatedPropertyPresent(environment, "simplematch.fix-gateway.data-plane-enabled",
      "simplematch.quickfix-gateway.data-plane-enabled");
    warnIfDeprecatedPropertyPresent(environment, "simplematch.fix-gateway.replay-enabled",
      "simplematch.quickfix-gateway.replay-enabled");
    warnIfDeprecatedPropertyPresent(environment, "SIMPLEMATCH_FIX_QUICKFIX_CONFIG",
      "SIMPLEMATCH_QUICKFIX_GATEWAY_QUICKFIX_CONFIG");
    warnIfDeprecatedPropertyPresent(environment, "SIMPLEMATCH_FIX_WAL_PATH",
      "SIMPLEMATCH_QUICKFIX_GATEWAY_WAL_PATH");
    warnIfDeprecatedPropertyPresent(environment, "quickfix-config",
      "simplematch.quickfix-gateway.quickfix-config-path");
    warnIfDeprecatedPropertyPresent(environment, "quickfix.config",
      "simplematch.quickfix-gateway.quickfix-config-path");
    warnIfDeprecatedPropertyPresent(environment, "wal",
      "simplematch.quickfix-gateway.wal-path");

    final MutablePropertySources propertySources = environment.getPropertySources();
    final Map<String, Object> aliases = legacyAliases(environment);
    if (!aliases.isEmpty()) {
      propertySources.addFirst(new MapPropertySource(ALIAS_SOURCE_NAME, aliases));
    }

    resolveConfigPath(environment).ifPresent(path -> {
      final Map<String, Object> flattened = loadJson(path);
      if (!flattened.isEmpty()) {
        propertySources.addLast(new MapPropertySource(JSON_SOURCE_NAME, flattened));
      }
    });
  }

  @Override
  public int getOrder() {
    return Ordered.HIGHEST_PRECEDENCE + 10;
  }

  private Optional<Path> resolveConfigPath(ConfigurableEnvironment environment) {
    final String appConfig = environment.getProperty("app-config");
    final String appConfigDot = environment.getProperty("app.config");
    final String commandLinePath = firstNonBlank(appConfig, appConfigDot);
    if (commandLinePath != null) {
      return Optional.of(requireExisting(resolvePath(commandLinePath), "--app-config"));
    }

    final String envPath = lookup(environment, "SIMPLEMATCH_CONFIG");
    if (envPath != null && !envPath.isBlank()) {
      return Optional.of(requireExisting(resolvePath(envPath), "SIMPLEMATCH_CONFIG"));
    }

    final Path defaultPath = resolvePath(DEFAULT_CONFIG_PATH);
    if (Files.exists(defaultPath)) {
      return Optional.of(defaultPath);
    }

    return Optional.empty();
  }

  private Path requireExisting(Path path, String source) {
    if (!Files.exists(path)) {
      throw new IllegalStateException("config file from " + source + " not found: " + path);
    }
    return path;
  }

  private Path resolvePath(String rawPath) {
    final Path path = Path.of(rawPath);
    if (path.isAbsolute()) {
      return path.normalize();
    }

    final Path workspaceRoot = findWorkspaceRoot();
    return workspaceRoot.resolve(path).normalize();
  }

  private Path findWorkspaceRoot() {
    Path current = Path.of("").toAbsolutePath().normalize();
    while (current != null) {
      if (Files.exists(current.resolve("settings.gradle.kts")) || Files.exists(current.resolve(".git"))) {
        return current;
      }
      current = current.getParent();
    }
    return Path.of("").toAbsolutePath().normalize();
  }

  private Map<String, Object> loadJson(Path path) {
    try {
      final Map<String, Object> raw = objectMapper.readValue(path.toFile(), new TypeReference<>() {});
      if (raw.containsKey("fixGateway")) {
        logger.warning(() -> "Deprecated config key 'fixGateway' detected in " + path
            + "; prefer 'quickfixGateway'.");
      }
      final Map<String, Object> flattened = new LinkedHashMap<>();
      flatten("simplematch", raw, flattened);
      return flattened;
    } catch (IOException e) {
      throw new IllegalStateException("failed to load config file: " + path, e);
    }
  }

  private void flatten(String prefix, Object value, Map<String, Object> output) {
    if (value instanceof Map<?, ?> mapValue) {
      for (Map.Entry<?, ?> entry : mapValue.entrySet()) {
        final String childPrefix = prefix + "." + toKebabCase(String.valueOf(entry.getKey()));
        flatten(childPrefix, entry.getValue(), output);
      }
      return;
    }

    if (value instanceof List<?> listValue) {
      for (int index = 0; index < listValue.size(); index++) {
        flatten(prefix + "[" + index + "]", listValue.get(index), output);
      }
      return;
    }

    output.put(prefix, value);
  }

  private Map<String, Object> legacyAliases(ConfigurableEnvironment environment) {
    final Map<String, Object> aliases = new LinkedHashMap<>();

    alias(environment, aliases, "simplematch.env", List.of("SIMPLEMATCH_ENV"));
    alias(environment, aliases, "simplematch.quickfix-gateway.quickfix-config-path", List.of(
      "simplematch.fix-gateway.quickfix-config-path",
        "quickfix-config",
        "quickfix.config",
      "SIMPLEMATCH_QUICKFIX_GATEWAY_QUICKFIX_CONFIG",
        "SIMPLEMATCH_FIX_QUICKFIX_CONFIG"));
    alias(environment, aliases, "simplematch.quickfix-gateway.wal-path", List.of(
      "simplematch.fix-gateway.wal-path",
        "wal",
      "SIMPLEMATCH_QUICKFIX_GATEWAY_WAL_PATH",
        "SIMPLEMATCH_FIX_WAL_PATH"));
    alias(environment, aliases, "simplematch.quickfix-gateway.owner-id", List.of(
      "SIMPLEMATCH_QUICKFIX_GATEWAY_OWNER_ID"));
    alias(environment, aliases, "simplematch.quickfix-gateway.acceptor-enabled", List.of(
      "simplematch.fix-gateway.acceptor-enabled"));
    alias(environment, aliases, "simplematch.quickfix-gateway.data-plane-enabled", List.of(
      "simplematch.fix-gateway.data-plane-enabled"));
    alias(environment, aliases, "simplematch.quickfix-gateway.replay-enabled", List.of(
      "simplematch.fix-gateway.replay-enabled"));

    return aliases;
  }

  private void alias(
      ConfigurableEnvironment environment,
      Map<String, Object> aliases,
      String targetKey,
      List<String> sourceKeys) {
    for (String sourceKey : sourceKeys) {
      final String value = lookup(environment, sourceKey);
      if (value != null && !value.isBlank()) {
        aliases.put(targetKey, value);
        return;
      }
    }
  }

  private void warnIfPresent(ConfigurableEnvironment environment, String deprecatedKey, String replacementKey) {
    final String value = lookup(environment, deprecatedKey);
    if (value != null && !value.isBlank()) {
      logger.warning(() -> "Deprecated config alias '" + deprecatedKey + "' is in use; prefer '"
          + replacementKey + "'.");
    }
  }

  @Nullable
  private String lookup(ConfigurableEnvironment environment, String key) {
    if (key == null || key.isBlank()) {
      return null;
    }

    final String value = environment.getProperty(key, String.class);

    if (value != null && !value.isBlank()) {
      return value;
    }

    final Object rawEnvValue = environment.getSystemEnvironment().get(key);
    if (rawEnvValue instanceof String envValue && !envValue.isBlank()) {
      return envValue;
    }
    return null;
  }

  private String firstNonBlank(String... values) {
    for (String value : values) {
      if (value != null && !value.isBlank()) {
        return value;
      }
    }
    return null;
  }

  private String toKebabCase(String value) {
    final StringBuilder builder = new StringBuilder();
    for (int index = 0; index < value.length(); index++) {
      final char current = value.charAt(index);
      if (Character.isUpperCase(current)) {
        if (index > 0 && builder.charAt(builder.length() - 1) != '-') {
          builder.append('-');
        }
        builder.append(Character.toLowerCase(current));
        continue;
      }

      if (current == '_' || current == ' ') {
        builder.append('-');
        continue;
      }

      builder.append(Character.toLowerCase(current));
    }
    return builder.toString().replace("--", "-");
  }
}