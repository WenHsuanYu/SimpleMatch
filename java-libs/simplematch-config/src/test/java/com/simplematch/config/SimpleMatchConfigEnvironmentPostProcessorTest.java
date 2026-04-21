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

  // 驗證環境後處理器會合併 JSON、環境變數與舊版 CLI 覆寫來源。
  // 情境：同時提供 JSON、SIMPLEMATCH_* 與 commandLineArgs，確認最終綁定結果遵守優先順序。
  @DisplayName("環境後處理器會合併 JSON 與舊版覆寫來源")
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
            "quickfixConfigPath": "config/fix/from-json.cfg",
            "walPath": "data/fix/wal/from-json.wal"
          }
        }
        """);

    final ConfigurableEnvironment environment = new StandardEnvironment();
    final Map<String, Object> systemEnvironment = new HashMap<>();
    systemEnvironment.put("SIMPLEMATCH_CONFIG", jsonPath.toString());
    systemEnvironment.put("SIMPLEMATCH_ENV", "env-override");
    systemEnvironment.put("SIMPLEMATCH_QUICKFIX_GATEWAY_WAL_PATH", "data/fix/wal/from-env.wal");
    environment.getPropertySources().replace(
        StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
        new MapPropertySource(
            StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
        systemEnvironment));
    final Map<String, Object> commandLineArgs = new HashMap<>();
    commandLineArgs.put("quickfix-config", "config/fix/from-cli.cfg");
    commandLineArgs.put("wal", "data/fix/wal/from-cli.wal");
    environment.getPropertySources().addFirst(
      new MapPropertySource("commandLineArgs", commandLineArgs));

    postProcessor.postProcessEnvironment(environment, new SpringApplication(Object.class));

    final SimpleMatchConfig config = Binder.get(environment)
        .bind("simplematch", Bindable.of(SimpleMatchConfig.class))
      .orElseThrow(() -> new IllegalStateException("simplematch config should bind"));

    assertEquals("env-override", config.getEnv());
    assertEquals("json-broker:9092", config.getKafka().getBrokers());
    assertEquals("config/fix/from-cli.cfg", config.getQuickfixGateway().getQuickfixConfigPath());
    assertEquals("data/fix/wal/from-cli.wal", config.getQuickfixGateway().getWalPath());
  }

  // 驗證 app-config 命令列參數會優先於 SIMPLEMATCH_CONFIG 指向的設定檔。
  // 情境：同時提供環境設定檔路徑與 CLI 設定檔路徑，確認最終採用 CLI 指定檔案。
  @DisplayName("app-config 參數優先於 SIMPLEMATCH_CONFIG")
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

  // 驗證舊版 Spring 屬性名稱會被映射成新的 quickfix-gateway 命名空間。
  // 情境：只提供 legacy fix-gateway 屬性，確認新屬性鍵可讀取到相同值。
  @DisplayName("舊版 Spring 屬性名稱會映射到新 quickfix-gateway 命名空間")
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