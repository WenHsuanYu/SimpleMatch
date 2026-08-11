#include "simplematch/matching/runtime/kubernetes_lease_ownership_adapter.hpp"
#include "simplematch/matching/runtime/rdkafka_runtime_adapter.hpp"

#include <chrono>

#include <gtest/gtest.h>

namespace simplematch::matching {
namespace {

TEST(KubernetesLeaseTimestampTest, UsesSixFractionalDigitsAcceptedByTheApiServer) {
  const auto value = std::chrono::system_clock::time_point(std::chrono::seconds(123) +
                                                            std::chrono::microseconds(456789));

  EXPECT_EQ(detail::format_kubernetes_lease_timestamp(value),
            "1970-01-01T00:02:03.456789Z");
}

TEST(RdkafkaRuntimeAdapterTest, GivesRecoveryMetadataQueriesAtLeastOneSecond) {
  EXPECT_EQ(detail::kafka_offset_query_timeout(std::chrono::milliseconds(100)),
            std::chrono::seconds(1));
  EXPECT_EQ(detail::kafka_offset_query_timeout(std::chrono::milliseconds(1500)),
            std::chrono::milliseconds(1500));
}

} // namespace
} // namespace simplematch::matching
