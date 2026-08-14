#pragma once

#include "simplematch/matching/core/deterministic_matching_core.hpp"

#include <string_view>

namespace simplematch::matching::test_support {

inline CoreUuid uuid(std::string_view value) {
  return CoreUuid::parse(value).value();
}

inline CoreInstrument instrument() {
  return CoreInstrument::create("XTAI", "2330").value();
}

inline MatchingCommandContext context() {
  return MatchingCommandContext::create(
             "2026-08-11:7cd06c51691bcde248e606ed1adfaddc4bd10ece582a6803fd2f04155a032943",
             "2026-08-11-regular",
             "stable-least-loaded-v1",
             0)
      .value();
}

} // namespace simplematch::matching::test_support
