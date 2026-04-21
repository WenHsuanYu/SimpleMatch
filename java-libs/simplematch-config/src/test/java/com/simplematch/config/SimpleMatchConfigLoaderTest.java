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

  // 驗證明確傳入的覆寫值會覆蓋環境變數與 JSON 設定。
  // 情境：同時提供 JSON、環境變數與 CLI 覆寫，確認最終採用明確覆寫內容。
  @DisplayName("明確覆寫值優先於環境變數與 JSON 設定")
  @Test
  void explicitOverridesBeatEnvironmentAndJson() throws IOException {
    final Path jsonConfig = tempDir.resolve("simplematch.json");
    Files.writeString(jsonConfig, """
        {
          "env": "json",
          "quickfixGateway": {
            "quickfixConfigPath": "json/fix.cfg",
            "walPath": "json/inbound.wal"
          }
        }
        """);

    final Map<String, String> environment = new HashMap<>();
    environment.put("SIMPLEMATCH_CONFIG", jsonConfig.toString());
    environment.put("SIMPLEMATCH_ENV", "env");
    environment.put("SIMPLEMATCH_QUICKFIX_GATEWAY_QUICKFIX_CONFIG", "env/fix.cfg");
    environment.put("SIMPLEMATCH_QUICKFIX_GATEWAY_WAL_PATH", "env/inbound.wal");

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

  // 驗證明確指定的 app config 路徑會優先於環境變數中的設定檔路徑。
  // 情境：同時提供 SIMPLEMATCH_CONFIG 與 overrides.appConfigPath，確認載入 override 指向的檔案。
  @DisplayName("明確指定的設定檔路徑優先於環境變數路徑")
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

  // 驗證在沒有明確覆寫時，環境變數會覆蓋 JSON 內的對應設定值。
  // 情境：提供 JSON 與環境變數，確認 env 與 quickfix 路徑採用環境變數內容。
  @DisplayName("未提供明確覆寫時環境變數優先於 JSON")
  @Test
  void environmentOverridesJsonWhenNoExplicitOverrides() throws IOException {
    final Path jsonConfig = tempDir.resolve("from-env.json");
    Files.writeString(jsonConfig, """
        {
          "env": "json",
          "quickfixGateway": {
            "quickfixConfigPath": "json/fix.cfg",
            "walPath": "json/inbound.wal"
          }
        }
        """);

    final Map<String, String> environment = new HashMap<>();
    environment.put("SIMPLEMATCH_CONFIG", jsonConfig.toString());
    environment.put("SIMPLEMATCH_ENV", "env");
    environment.put("SIMPLEMATCH_QUICKFIX_GATEWAY_QUICKFIX_CONFIG", "env/fix.cfg");
    environment.put("SIMPLEMATCH_QUICKFIX_GATEWAY_WAL_PATH", "env/inbound.wal");

    final LoadedSimpleMatchConfig loaded = loader.load(new SimpleMatchConfigLoadRequest(
        tempDir,
        environment,
        new SimpleMatchConfigOverrides(null, null, null, null)));

    assertEquals("env", loaded.config().getEnv());
    assertEquals("env/fix.cfg", loaded.config().getQuickfixGateway().getQuickfixConfigPath());
    assertEquals("env/inbound.wal", loaded.config().getQuickfixGateway().getWalPath());
    assertEquals(jsonConfig, loaded.appConfigPath().orElseThrow());
  }

  // 驗證未提供外部路徑時，loader 會回退到預設 config/simplematch.json。
  // 情境：在 tempDir 建立預設設定檔，確認 loader 能自動找到並載入它。
  @DisplayName("未指定路徑時會載入預設設定檔")
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

  // 驗證完全沒有 JSON、環境變數或覆寫時，系統仍保留內建預設值。
  // 情境：只提供空環境與空 overrides，確認設定路徑與 env 來自預設配置。
  @DisplayName("完全未提供外部設定時會保留內建預設值")
  @Test
  void builtInDefaultsRemainWhenNoJsonEnvironmentOrOverridesExist() {
    final LoadedSimpleMatchConfig loaded = loader.load(new SimpleMatchConfigLoadRequest(
        tempDir,
        Map.of(),
        new SimpleMatchConfigOverrides(null, null, null, null)));

    assertTrue(loaded.appConfigPath().isEmpty());
    assertEquals("dev", loaded.config().getEnv());
    assertEquals("config/fix/acceptor.cfg", loaded.config().getQuickfixGateway().getQuickfixConfigPath());
    assertEquals("data/fix/wal/inbound.wal", loaded.config().getQuickfixGateway().getWalPath());
  }

  // 驗證舊版 fixGateway JSON 與環境變數別名仍能映射到新的 quickfixGateway 設定欄位。
  // 情境：使用 legacy fixGateway 欄位名稱載入設定，確認新命名空間仍能讀到值。
  @DisplayName("舊版 FIX Gateway 別名仍會映射到新設定欄位")
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