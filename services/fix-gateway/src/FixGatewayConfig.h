#pragma once

#include <string>

namespace simplematch::fixgw {

struct FixGatewayConfig {
  std::string env = "dev";
  std::string quickfixConfigPath = "config/fix/acceptor.cfg";
  std::string walPath = "data/fix/wal/inbound.wal";
  std::string appConfigPath; // empty means: no JSON file loaded
};

// Loads config from (in precedence order):
//   1) CLI flags
//   2) environment variables
//   3) JSON config file (optional)
//   4) defaults
//
// CLI compatibility:
//   - If the first argument is a path (and not a flag), it is treated as the QuickFIX cfg path.
// Flags:
//   --app-config <path>
//   --quickfix-config <path>
//   --wal <path>
FixGatewayConfig LoadFixGatewayConfig(int argc, char** argv);

} // namespace simplematch::fixgw
