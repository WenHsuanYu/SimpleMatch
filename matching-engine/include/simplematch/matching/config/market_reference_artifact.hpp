#pragma once

#include <string>
#include <string_view>

namespace simplematch::matching {

enum class MarketReferenceArtifactAction { kProceed, kStop };

/** Result of verifying the final startup artifact before Matching may own a partition. */
struct MarketReferenceArtifactDecision {
  MarketReferenceArtifactAction action;
  std::string reason;

  bool operator==(const MarketReferenceArtifactDecision &) const = default;
};

/** Validates the shared JSON artifact and its external SHA-256 before native startup. */
class MarketReferenceArtifactLoader {
public:
  MarketReferenceArtifactDecision load(std::string_view artifact_json,
                                       std::string_view external_checksum,
                                       std::string_view expected_trading_day) const;
};

} // namespace simplematch::matching
