#pragma once

#include "simplematch/matching/core/deterministic_matching_core.hpp"

#include <cstdint>
#include <string>
#include <string_view>

namespace simplematch::matching {

enum class MatchingCommandDecodeError {
  kNone,
  kInvalidPayload,
  kInvalidHeader,
  kInvalidArtifactIdentity,
  kInvalidCommand
};

/** Open-Barrier-only pins that stay outside the single-writer command value. */
struct MatchingCommandMetadata {
  std::int32_t command_schema_version{};
  std::int32_t event_schema_version{};
  std::int32_t event_identity_version{};
  std::string matching_image_digest;
};

/** Result of translating one protobuf command into the fixed-value matching-core boundary. */
struct MatchingCommandDecodeResult {
  MatchingCommandDecodeError error;
  CoreCommand command;
  MatchingCommandMetadata metadata;

  [[nodiscard]] bool accepted() const {
    return error == MatchingCommandDecodeError::kNone;
  }
};

/** Decodes and validates final matching.commands protobuf bytes outside the matching hot path. */
class MatchingCommandDecoder {
public:
  [[nodiscard]] MatchingCommandDecodeResult decode(std::string_view payload) const;
};

} // namespace simplematch::matching
