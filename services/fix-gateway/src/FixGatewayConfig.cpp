#include "FixGatewayConfig.h"

#include <cstdlib>
#include <filesystem>
#include <optional>
#include <stdexcept>
#include <string>
#include <string_view>

#if defined(SIMPLEMATCH_HAS_NLOHMANN_JSON)
#include <fstream>
#include <nlohmann/json.hpp>
#endif

namespace simplematch::fixgw {

namespace {
struct CliArgs {
  std::optional<std::string> appConfigPath;
  std::optional<std::string> quickfixConfigPath;
  std::optional<std::string> walPath;
};

std::optional<std::string> getEnv(const char* name) {
  const char* value = std::getenv(name);
  if (value == nullptr || *value == '\0') {
    return std::nullopt;
  }
  return std::string(value);
}

bool isFlag(std::string_view s) {
  return s.rfind("--", 0) == 0;
}

std::string requireArg(int argc, char** argv, int& i, const char* flag) {
  if (i + 1 >= argc) {
    throw std::runtime_error(std::string("missing value for ") + flag);
  }
  ++i;
  return argv[i];
}

CliArgs parseCli(int argc, char** argv) {
  CliArgs cli;

  // CLI compatibility:
  // - If an argument is a path (and not a flag), treat it as the QuickFIX cfg path.
  // - Flags override earlier values; last wins.
  for (int i = 1; i < argc; ++i) {
    const std::string_view arg{argv[i]};

    if (!isFlag(arg)) {
      cli.quickfixConfigPath = std::string(arg);
      continue;
    }

    if (arg == "--app-config") {
      cli.appConfigPath = requireArg(argc, argv, i, "--app-config");
      continue;
    }

    if (arg == "--quickfix-config") {
      cli.quickfixConfigPath = requireArg(argc, argv, i, "--quickfix-config");
      continue;
    }

    if (arg == "--wal") {
      cli.walPath = requireArg(argc, argv, i, "--wal");
      continue;
    }

    throw std::runtime_error("unknown flag: " + std::string(arg));
  }

  return cli;
}

#if defined(SIMPLEMATCH_HAS_NLOHMANN_JSON)
void applyJson(FixGatewayConfig& cfg, const nlohmann::json& j) {
  if (!j.is_object()) {
    return;
  }

  if (j.contains("env") && j["env"].is_string()) {
    cfg.env = j["env"].get<std::string>();
  }

  if (j.contains("fixGateway") && j["fixGateway"].is_object()) {
    const auto& fg = j["fixGateway"];
    if (fg.contains("quickfixConfigPath") && fg["quickfixConfigPath"].is_string()) {
      cfg.quickfixConfigPath = fg["quickfixConfigPath"].get<std::string>();
    }
    if (fg.contains("walPath") && fg["walPath"].is_string()) {
      cfg.walPath = fg["walPath"].get<std::string>();
    }
  }
}
#endif

void applyEnv(FixGatewayConfig& cfg) {
  if (auto v = getEnv("SIMPLEMATCH_ENV"); v.has_value()) {
    cfg.env = *v;
  }
  if (auto v = getEnv("SIMPLEMATCH_FIX_QUICKFIX_CONFIG"); v.has_value()) {
    cfg.quickfixConfigPath = *v;
  }
  if (auto v = getEnv("SIMPLEMATCH_FIX_WAL_PATH"); v.has_value()) {
    cfg.walPath = *v;
  }
}

void applyCli(FixGatewayConfig& cfg, const CliArgs& cli) {
  if (cli.quickfixConfigPath.has_value()) {
    cfg.quickfixConfigPath = *cli.quickfixConfigPath;
  }
  if (cli.walPath.has_value()) {
    cfg.walPath = *cli.walPath;
  }
}

void validateAndPrepare(const FixGatewayConfig& cfg) {
  if (!std::filesystem::exists(std::filesystem::path(cfg.quickfixConfigPath))) {
    throw std::runtime_error("QuickFIX config not found: " + cfg.quickfixConfigPath);
  }
  const std::filesystem::path walPath(cfg.walPath);
  if (walPath.has_parent_path()) {
    std::filesystem::create_directories(walPath.parent_path());
  }
}

} // namespace

FixGatewayConfig LoadFixGatewayConfig(int argc, char** argv) {
  FixGatewayConfig cfg;

  const CliArgs cli = parseCli(argc, argv);

  // JSON selection precedence:
  //   1) CLI --app-config
  //   2) env SIMPLEMATCH_CONFIG
  //   3) default file (if it exists)
  std::string jsonPath;
  if (cli.appConfigPath.has_value()) {
    jsonPath = *cli.appConfigPath;
  } else if (auto v = getEnv("SIMPLEMATCH_CONFIG"); v.has_value()) {
    jsonPath = *v;
  } else if (std::filesystem::exists("config/simplematch.json")) {
    jsonPath = "config/simplematch.json";
  }

  // Merge precedence (highest last): defaults -> JSON -> env -> CLI
  if (!jsonPath.empty()) {
#if !defined(SIMPLEMATCH_HAS_NLOHMANN_JSON)
    throw std::runtime_error(
        "JSON config requested but nlohmann-json is not enabled. Build with vcpkg (nlohmann-json) or unset SIMPLEMATCH_CONFIG.");
#else
    std::ifstream in(jsonPath);
    if (!in.is_open()) {
      throw std::runtime_error("failed to open config file: " + jsonPath);
    }
    nlohmann::json j;
    in >> j;
    applyJson(cfg, j);

    // Track which JSON file was loaded.
    cfg.appConfigPath = jsonPath;
#endif
  }

  applyEnv(cfg);
  applyCli(cfg, cli);

  validateAndPrepare(cfg);
  return cfg;
}

} // namespace simplematch::fixgw
