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

TEST(RdkafkaRuntimeAdapterTest, RetriesOnlyCoordinatorTransitions) {
  EXPECT_TRUE(detail::kafka_offset_query_should_retry(RD_KAFKA_RESP_ERR_NOT_COORDINATOR));
  EXPECT_FALSE(detail::kafka_offset_query_should_retry(RD_KAFKA_RESP_ERR__TIMED_OUT));
  EXPECT_FALSE(detail::kafka_offset_query_should_retry(RD_KAFKA_RESP_ERR__TRANSPORT));
}

TEST(RdkafkaRuntimeAdapterTest, UsesBoundedExponentialCoordinatorBackoff) {
  EXPECT_EQ(detail::kafka_offset_query_max_attempts(), 5U);
  EXPECT_EQ(detail::kafka_offset_query_backoff(0), std::chrono::milliseconds(100));
  EXPECT_EQ(detail::kafka_offset_query_backoff(1), std::chrono::milliseconds(200));
  EXPECT_EQ(detail::kafka_offset_query_backoff(2), std::chrono::milliseconds(400));
  EXPECT_EQ(detail::kafka_offset_query_backoff(3), std::chrono::milliseconds(800));
  EXPECT_EQ(detail::kafka_offset_query_backoff(99), std::chrono::milliseconds(800));
}

TEST(RdkafkaRuntimeAdapterTest, RetriesACoordinatorTransitionBeforeReturningTheOffset) {
  std::size_t attempts = 0;
  const auto result = detail::kafka_offset_query_with_retry([&] {
    ++attempts;
    if (attempts < 3) {
      return detail::KafkaOffsetQueryAttempt{RD_KAFKA_RESP_ERR_NOT_COORDINATOR, std::nullopt, true};
    }
    return detail::KafkaOffsetQueryAttempt{RD_KAFKA_RESP_ERR_NO_ERROR, 42, false};
  });

  EXPECT_EQ(attempts, 3U);
  EXPECT_EQ(result.error, RD_KAFKA_RESP_ERR_NO_ERROR);
  ASSERT_TRUE(result.committed_offset.has_value());
  EXPECT_EQ(*result.committed_offset, 42);
}

TEST(RdkafkaRuntimeAdapterTest, StopsImmediatelyOnANonRetryableOffsetError) {
  std::size_t attempts = 0;
  const auto result = detail::kafka_offset_query_with_retry([&] {
    ++attempts;
    return detail::KafkaOffsetQueryAttempt{RD_KAFKA_RESP_ERR__TIMED_OUT, std::nullopt, true};
  });

  EXPECT_EQ(attempts, 1U);
  EXPECT_EQ(result.error, RD_KAFKA_RESP_ERR__TIMED_OUT);
}

TEST(RdkafkaRuntimeAdapterTest, FailsClosedAfterTheCoordinatorRetryBudgetIsExhausted) {
  std::size_t attempts = 0;
  const auto result = detail::kafka_offset_query_with_retry([&] {
    ++attempts;
    return detail::KafkaOffsetQueryAttempt{RD_KAFKA_RESP_ERR_NOT_COORDINATOR, std::nullopt, true};
  });

  EXPECT_EQ(attempts, detail::kafka_offset_query_max_attempts());
  EXPECT_EQ(result.error, RD_KAFKA_RESP_ERR_NOT_COORDINATOR);
  EXPECT_TRUE(result.error_from_query);
}

} // namespace
} // namespace simplematch::matching
