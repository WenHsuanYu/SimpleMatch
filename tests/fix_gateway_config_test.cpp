#include <gtest/gtest.h>

#include "FixGatewayConfig.h"

#include <chrono>
#include <cstdlib>
#include <filesystem>
#include <fstream>
#include <string>
#include <string_view>
#include <vector>

namespace {

class ScopedEnvVar {
public:
  ScopedEnvVar(const char* name, std::string value) : name_(name) {
    const char* old = std::getenv(name);
    if (old != nullptr) {
      oldValue_ = std::string(old);
      hadOld_ = true;
    }

    if (::setenv(name, value.c_str(), 1) != 0) {
      throw std::runtime_error("setenv failed");
    }
  }

  static ScopedEnvVar Unset(const char* name) {
    return ScopedEnvVar(name);
  }

  ~ScopedEnvVar() {
    if (name_ == nullptr) {
      return;
    }

    if (hadOld_) {
      ::setenv(name_, oldValue_.c_str(), 1);
    } else {
      ::unsetenv(name_);
    }
  }

  ScopedEnvVar(const ScopedEnvVar&) = delete;
  ScopedEnvVar& operator=(const ScopedEnvVar&) = delete;
  ScopedEnvVar(ScopedEnvVar&&) = delete;
  ScopedEnvVar& operator=(ScopedEnvVar&&) = delete;

private:
  explicit ScopedEnvVar(const char* name) : name_(name) {
    const char* old = std::getenv(name);
    if (old != nullptr) {
      oldValue_ = std::string(old);
      hadOld_ = true;
    }

    ::unsetenv(name);
  }

  const char* name_;
  bool hadOld_ = false;
  std::string oldValue_;
};

std::filesystem::path makeUniqueTempDir(std::string_view prefix) {
  const auto base = std::filesystem::temp_directory_path();
  const auto now = std::chrono::steady_clock::now().time_since_epoch().count();
  const auto dir = base / (std::string(prefix) + "_" + std::to_string(static_cast<long long>(now)));
  std::filesystem::create_directories(dir);
  return dir;
}

void writeTextFile(const std::filesystem::path& path, const std::string& text) {
  std::filesystem::create_directories(path.parent_path());
  std::ofstream out(path);
  if (!out.is_open()) {
    throw std::runtime_error("failed to open file for write");
  }
  out << text;
}

#if defined(SIMPLEMATCH_HAS_NLOHMANN_JSON)
std::string makeConfigJson(const std::string& env, const std::string& quickfixCfg, const std::string& walPath) {
  // Keep it minimal; only fields supported by applyJson().
  return std::string("{\n") +
         "  \"env\": \"" + env + "\",\n" +
         "  \"fixGateway\": {\n" +
         "    \"quickfixConfigPath\": \"" + quickfixCfg + "\",\n" +
         "    \"walPath\": \"" + walPath + "\"\n" +
         "  }\n" +
         "}\n";
}
#endif

static std::vector<char*> makeArgv(std::vector<std::string>& storage) {
  std::vector<char*> argv;
  argv.reserve(storage.size());
  for (auto& s : storage) {
    argv.push_back(s.data());
  }
  return argv;
}

TEST(FixGatewayConfig, CliOverridesEnvOverridesJsonOverridesDefaults) {
#if !defined(SIMPLEMATCH_HAS_NLOHMANN_JSON)
  GTEST_SKIP() << "Requires nlohmann-json (SIMPLEMATCH_HAS_NLOHMANN_JSON)";
#else
  const auto tmp = makeUniqueTempDir("simplematch_fixgw_cfg");

  const auto jsonCfg = (tmp / "json" / "acceptor.cfg");
  const auto envCfg = (tmp / "env" / "acceptor.cfg");
  const auto cliCfg = (tmp / "cli" / "acceptor.cfg");
  writeTextFile(jsonCfg, "# json cfg\n");
  writeTextFile(envCfg, "# env cfg\n");
  writeTextFile(cliCfg, "# cli cfg\n");

  const auto jsonWal = (tmp / "json" / "wal" / "in.wal");
  const auto envWal = (tmp / "env" / "wal" / "in.wal");
  const auto cliWal = (tmp / "cli" / "wal" / "in.wal");

  const auto jsonPath = (tmp / "config.json");
  writeTextFile(jsonPath, makeConfigJson("json", jsonCfg.string(), jsonWal.string()));

  // Ensure default auto-discovery doesn't interfere.
  const ScopedEnvVar unsetEnv = ScopedEnvVar::Unset("SIMPLEMATCH_ENV");
  const ScopedEnvVar unsetFixCfg = ScopedEnvVar::Unset("SIMPLEMATCH_FIX_QUICKFIX_CONFIG");
  const ScopedEnvVar unsetWal = ScopedEnvVar::Unset("SIMPLEMATCH_FIX_WAL_PATH");
  const ScopedEnvVar unsetConfig = ScopedEnvVar::Unset("SIMPLEMATCH_CONFIG");

  const ScopedEnvVar setEnv("SIMPLEMATCH_ENV", "env");
  const ScopedEnvVar setFixCfg("SIMPLEMATCH_FIX_QUICKFIX_CONFIG", envCfg.string());
  const ScopedEnvVar setWal("SIMPLEMATCH_FIX_WAL_PATH", envWal.string());
  const ScopedEnvVar setConfig("SIMPLEMATCH_CONFIG", jsonPath.string());

  std::vector<std::string> args;
  args.emplace_back("fix-gateway");
  args.emplace_back("--quickfix-config");
  args.emplace_back(cliCfg.string());
  args.emplace_back("--wal");
  args.emplace_back(cliWal.string());

  auto argv = makeArgv(args);
  const auto cfg = simplematch::fixgw::LoadFixGatewayConfig(static_cast<int>(argv.size()), argv.data());

  // env overrides JSON (no CLI flag exists for env).
  EXPECT_EQ(cfg.env, "env");

  // CLI overrides env and JSON.
  EXPECT_EQ(cfg.quickfixConfigPath, cliCfg.string());
  EXPECT_EQ(cfg.walPath, cliWal.string());

  // JSON file should be tracked.
  EXPECT_EQ(cfg.appConfigPath, jsonPath.string());
#endif
}

TEST(FixGatewayConfig, CliAppConfigPathOverridesEnvConfigPath) {
#if !defined(SIMPLEMATCH_HAS_NLOHMANN_JSON)
  GTEST_SKIP() << "Requires nlohmann-json (SIMPLEMATCH_HAS_NLOHMANN_JSON)";
#else
  const auto tmp = makeUniqueTempDir("simplematch_fixgw_jsonsel");

  const auto json1Cfg = (tmp / "json1" / "acceptor.cfg");
  const auto json2Cfg = (tmp / "json2" / "acceptor.cfg");
  writeTextFile(json1Cfg, "# json1 cfg\n");
  writeTextFile(json2Cfg, "# json2 cfg\n");

  const auto json1Wal = (tmp / "json1" / "wal" / "in.wal");
  const auto json2Wal = (tmp / "json2" / "wal" / "in.wal");

  const auto json1Path = (tmp / "config1.json");
  const auto json2Path = (tmp / "config2.json");
  writeTextFile(json1Path, makeConfigJson("json1", json1Cfg.string(), json1Wal.string()));
  writeTextFile(json2Path, makeConfigJson("json2", json2Cfg.string(), json2Wal.string()));

  const ScopedEnvVar unsetEnv = ScopedEnvVar::Unset("SIMPLEMATCH_ENV");
  const ScopedEnvVar unsetFixCfg = ScopedEnvVar::Unset("SIMPLEMATCH_FIX_QUICKFIX_CONFIG");
  const ScopedEnvVar unsetWal = ScopedEnvVar::Unset("SIMPLEMATCH_FIX_WAL_PATH");
  const ScopedEnvVar unsetConfig = ScopedEnvVar::Unset("SIMPLEMATCH_CONFIG");

  const ScopedEnvVar setConfig("SIMPLEMATCH_CONFIG", json1Path.string());

  std::vector<std::string> args;
  args.emplace_back("fix-gateway");
  args.emplace_back("--app-config");
  args.emplace_back(json2Path.string());

  auto argv = makeArgv(args);
  const auto cfg = simplematch::fixgw::LoadFixGatewayConfig(static_cast<int>(argv.size()), argv.data());

  EXPECT_EQ(cfg.appConfigPath, json2Path.string());
  EXPECT_EQ(cfg.env, "json2");
  EXPECT_EQ(cfg.quickfixConfigPath, json2Cfg.string());
  EXPECT_EQ(cfg.walPath, json2Wal.string());
#endif
}

} // namespace
