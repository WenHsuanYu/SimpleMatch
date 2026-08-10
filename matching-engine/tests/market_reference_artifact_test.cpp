#include "simplematch/matching/config/market_reference_artifact.hpp"

#include <array>
#include <fstream>
#include <iterator>
#include <limits>
#include <memory>
#include <string>

#include <gtest/gtest.h>
#include <nlohmann/json.hpp>
#include <openssl/evp.h>

namespace simplematch::matching {
namespace {

std::string load_fixture(const std::string &name) {
  std::ifstream fixture(std::string(SIMPLEMATCH_MARKET_REFERENCE_FIXTURE_DIR) +
                        "/" + name);
  return {std::istreambuf_iterator<char>(fixture), {}};
}

std::string sha256(const std::string &content) {
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

TEST(MarketReferenceArtifactTest, ReadsSharedJavaFinalFixture) {
  MarketReferenceArtifactLoader loader;
  const auto artifact = load_fixture("market_reference.json");
  const auto checksum = load_fixture("market_reference.sha256");

  ASSERT_FALSE(artifact.empty());
  ASSERT_FALSE(checksum.empty());
  EXPECT_EQ(loader.load(artifact, checksum, "2026-08-11"),
            (MarketReferenceArtifactDecision{
                MarketReferenceArtifactAction::kProceed,
                "MARKET_REFERENCE_ARTIFACT_READY"}));
}

TEST(MarketReferenceArtifactTest, RejectsChecksumMismatchBeforeJsonParsing) {
  MarketReferenceArtifactLoader loader;

  EXPECT_EQ(loader.load("not json", std::string(64, 'a'), "2026-08-11"),
            (MarketReferenceArtifactDecision{
                MarketReferenceArtifactAction::kStop,
                "MARKET_REFERENCE_ARTIFACT_CHECKSUM_MISMATCH"}));
}

TEST(MarketReferenceArtifactTest, StopsInsteadOfThrowingForOutOfRangePartition) {
  MarketReferenceArtifactLoader loader;
  auto artifact = nlohmann::json::parse(load_fixture("market_reference.json"));
  artifact["routingPolicy"]["assignments"][0]["partitionId"] =
      std::numeric_limits<std::int64_t>::max();
  const auto malformed_artifact = artifact.dump();

  EXPECT_EQ(loader.load(malformed_artifact, sha256(malformed_artifact), "2026-08-11"),
            (MarketReferenceArtifactDecision{
                MarketReferenceArtifactAction::kStop,
                "MARKET_REFERENCE_ARTIFACT_INVALID"}));
}

} // namespace
} // namespace simplematch::matching
