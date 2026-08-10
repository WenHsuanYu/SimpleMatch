#include "simplematch/matching/config/market_reference_artifact.hpp"

#include <array>
#include <cctype>
#include <cstdint>
#include <memory>
#include <set>
#include <string>
#include <string_view>

#include <nlohmann/json.hpp>
#include <openssl/evp.h>

namespace simplematch::matching {
namespace {

constexpr char kReady[] = "MARKET_REFERENCE_ARTIFACT_READY";
constexpr char kChecksumInvalid[] = "MARKET_REFERENCE_ARTIFACT_CHECKSUM_INVALID";
constexpr char kChecksumMismatch[] = "MARKET_REFERENCE_ARTIFACT_CHECKSUM_MISMATCH";
constexpr char kInvalid[] = "MARKET_REFERENCE_ARTIFACT_INVALID";
constexpr std::int32_t kPartitionCount = 15;
constexpr std::int32_t kMaximumInstrumentsPerPartition = 150;

using Json = nlohmann::json;

std::string trim(std::string_view value) {
  std::size_t first = 0;
  while (first < value.size() &&
         std::isspace(static_cast<unsigned char>(value[first]))) {
    ++first;
  }
  std::size_t last = value.size();
  while (last > first &&
         std::isspace(static_cast<unsigned char>(value[last - 1]))) {
    --last;
  }
  return std::string(value.substr(first, last - first));
}

bool is_lower_sha256(std::string_view value) {
  if (value.size() != 64) {
    return false;
  }
  for (const char character : value) {
    if (!((character >= '0' && character <= '9') ||
          (character >= 'a' && character <= 'f'))) {
      return false;
    }
  }
  return true;
}

std::string sha256(std::string_view content) {
  using Context = std::unique_ptr<EVP_MD_CTX, decltype(&EVP_MD_CTX_free)>;
  Context context(EVP_MD_CTX_new(), EVP_MD_CTX_free);
  if (!context || EVP_DigestInit_ex(context.get(), EVP_sha256(), nullptr) != 1 ||
      EVP_DigestUpdate(context.get(), content.data(), content.size()) != 1) {
    return {};
  }
  std::array<unsigned char, EVP_MAX_MD_SIZE> digest{};
  unsigned int digest_size = 0;
  if (EVP_DigestFinal_ex(context.get(), digest.data(), &digest_size) != 1 ||
      digest_size != 32) {
    return {};
  }
  constexpr char kHex[] = "0123456789abcdef";
  std::string encoded;
  encoded.reserve(digest_size * 2);
  for (std::size_t index = 0; index < digest_size; ++index) {
    encoded.push_back(kHex[digest[index] >> 4]);
    encoded.push_back(kHex[digest[index] & 0x0f]);
  }
  return encoded;
}

bool is_string(const Json &value, const char *field) {
  return value.contains(field) && value.at(field).is_string() &&
         !value.at(field).get_ref<const std::string &>().empty();
}

bool is_integer(const Json &value, const char *field) {
  return value.contains(field) && value.at(field).is_number_integer();
}

std::string instrument_key(const Json &instrument) {
  if (!instrument.is_object() || !is_string(instrument, "venueMic") ||
      !is_string(instrument, "symbol")) {
    return {};
  }
  return instrument.at("venueMic").get<std::string>() + '\x1f' +
         instrument.at("symbol").get<std::string>();
}

bool valid_metadata(const Json &metadata, std::string_view expected_trading_day) {
  return metadata.is_object() && is_integer(metadata, "schemaVersion") &&
         metadata.at("schemaVersion") == 1 && is_string(metadata, "releaseState") &&
         metadata.at("releaseState") == "FINAL" &&
         is_string(metadata, "tradingDay") &&
         metadata.at("tradingDay") == expected_trading_day &&
         is_string(metadata, "routingAlgorithmVersion") &&
         metadata.contains("sourceProvenance") &&
         metadata.at("sourceProvenance").is_array() &&
         !metadata.at("sourceProvenance").empty();
}

bool valid_market_rules(const Json &market_rules) {
  return market_rules.is_object() && is_string(market_rules, "ruleSetVersion") &&
         market_rules.at("ruleSetVersion") == "phase1-tw-cash-v1" &&
         is_string(market_rules, "currency") && market_rules.at("currency") == "TWD" &&
         market_rules.contains("rules") && market_rules.at("rules").is_array() &&
         market_rules.contains("tickTables") && market_rules.at("tickTables").is_array();
}

bool valid_eligible_instrument(const Json &instrument) {
  return is_string(instrument, "marketRuleId") &&
         instrument.at("marketRuleId") == "regular-board-common-stock" &&
         is_integer(instrument, "referencePriceUnits") &&
         is_integer(instrument, "lowerPriceLimitUnits") &&
         is_integer(instrument, "upperPriceLimitUnits") &&
         instrument.at("lowerPriceLimitUnits").get<std::int64_t>() <
             instrument.at("referencePriceUnits").get<std::int64_t>() &&
         instrument.at("referencePriceUnits").get<std::int64_t>() <
             instrument.at("upperPriceLimitUnits").get<std::int64_t>();
}

bool validate_instruments(const Json &snapshot,
                          std::set<std::string> *eligible_instruments) {
  if (!snapshot.is_object() || !snapshot.contains("instruments") ||
      !snapshot.at("instruments").is_array()) {
    return false;
  }
  std::set<std::string> all_instruments;
  for (const auto &instrument : snapshot.at("instruments")) {
    const auto key = instrument_key(instrument);
    if (key.empty() || !all_instruments.insert(key).second ||
        !is_string(instrument, "eligibility")) {
      return false;
    }
    const auto eligibility = instrument.at("eligibility").get<std::string>();
    if (eligibility == "ELIGIBLE") {
      if (!valid_eligible_instrument(instrument)) {
        return false;
      }
      eligible_instruments->insert(key);
    } else if (eligibility != "UNSUPPORTED" ||
               !is_string(instrument, "ineligibilityReason")) {
      return false;
    }
  }
  return !eligible_instruments->empty();
}

bool validate_routes(const Json &routing_policy,
                     const Json &metadata,
                     const std::set<std::string> &eligible_instruments) {
  if (!routing_policy.is_object() ||
      !is_string(routing_policy, "algorithmVersion") ||
      routing_policy.at("algorithmVersion") !=
          metadata.at("routingAlgorithmVersion") ||
      !is_integer(routing_policy, "partitionCount") ||
      routing_policy.at("partitionCount") != kPartitionCount ||
      !is_integer(routing_policy, "maximumInstrumentsPerPartition") ||
      routing_policy.at("maximumInstrumentsPerPartition") !=
          kMaximumInstrumentsPerPartition ||
      !routing_policy.contains("assignments") ||
      !routing_policy.at("assignments").is_array()) {
    return false;
  }
  std::array<std::int32_t, kPartitionCount> loads{};
  std::set<std::string> routed_instruments;
  for (const auto &assignment : routing_policy.at("assignments")) {
    const auto key = instrument_key(assignment);
    if (key.empty() || !is_integer(assignment, "partitionId")) {
      return false;
    }
    const auto partition = assignment.at("partitionId").get<std::int32_t>();
    if (partition < 0 || partition >= kPartitionCount ||
        !eligible_instruments.contains(key) ||
        !routed_instruments.insert(key).second ||
        ++loads[partition] > kMaximumInstrumentsPerPartition) {
      return false;
    }
  }
  return routed_instruments == eligible_instruments;
}

bool valid_artifact(const Json &artifact, std::string_view expected_trading_day) {
  if (!artifact.is_object() || !artifact.contains("metadata") ||
      !artifact.contains("marketRules") || !artifact.contains("marketSnapshot") ||
      !artifact.contains("routingPolicy") ||
      !valid_metadata(artifact.at("metadata"), expected_trading_day) ||
      !valid_market_rules(artifact.at("marketRules"))) {
    return false;
  }
  std::set<std::string> eligible_instruments;
  return validate_instruments(artifact.at("marketSnapshot"),
                              &eligible_instruments) &&
         validate_routes(artifact.at("routingPolicy"), artifact.at("metadata"),
                         eligible_instruments);
}

} // namespace

MarketReferenceArtifactDecision MarketReferenceArtifactLoader::load(
    std::string_view artifact_json, std::string_view external_checksum,
    std::string_view expected_trading_day) const {
  const auto checksum = trim(external_checksum);
  if (!is_lower_sha256(checksum)) {
    return {MarketReferenceArtifactAction::kStop, kChecksumInvalid};
  }
  if (sha256(artifact_json) != checksum) {
    return {MarketReferenceArtifactAction::kStop, kChecksumMismatch};
  }
  const auto artifact = Json::parse(artifact_json, nullptr, false);
  if (artifact.is_discarded() || !valid_artifact(artifact, expected_trading_day)) {
    return {MarketReferenceArtifactAction::kStop, kInvalid};
  }
  return {MarketReferenceArtifactAction::kProceed, kReady};
}

} // namespace simplematch::matching
