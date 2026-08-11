#pragma once

#include "simplematch/matching/core/deterministic_matching_core.hpp"

#include <cstdint>
#include <optional>
#include <string>

namespace simplematch::matching {

/** One deterministic value ready for publication to matching.events. */
struct MatchingEventRecord {
  std::string key;
  std::int32_t partition_id;
  std::int64_t source_input_offset;
  std::int32_t output_index;
  std::string value;
};

/** Converts fixed core events to the final deterministic Protobuf record contract. */
class MatchingEventEncoder {
public:
  [[nodiscard]] std::optional<MatchingEventRecord> encode(
      const MatchingCommandContext &context,
      std::int64_t source_input_offset,
      const CoreEvent &event) const;
};

} // namespace simplematch::matching
