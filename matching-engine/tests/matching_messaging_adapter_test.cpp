#include <gtest/gtest.h>

#include <chrono>
#include <cstdint>
#include <limits>
#include <stdexcept>
#include <string_view>

#include <nlohmann/json.hpp>

#include "simplematch/matching/runtime/kubernetes_lease_ownership_adapter.hpp"
#include "simplematch/matching/runtime/rdkafka_runtime_adapter.hpp"

namespace simplematch::matching {
namespace {

TEST(KubernetesLeaseTimestampTest, UsesSixFractionalDigitsAcceptedByTheApiServer) {
  const auto value = std::chrono::system_clock::time_point(std::chrono::seconds(123) +
                                                            std::chrono::microseconds(456789));

  EXPECT_EQ(detail::format_kubernetes_lease_timestamp(value),
            "1970-01-01T00:02:03.456789Z");
}

TEST(KubernetesLeaseTimestampTest, ParsesCanonicalUtcWithUpToMicrosecondPrecision) {
  const auto midnight = std::chrono::system_clock::time_point(std::chrono::seconds(1767225600));
  struct Case {
    std::string_view timestamp;
    int expected_microseconds;
  };
  constexpr Case cases[] = {
      {"2026-01-01T00:00:00Z", 0},
      {"2026-01-01T00:00:00.1Z", 100000},
      {"2026-01-01T00:00:00.123Z", 123000},
      {"2026-01-01T00:00:00.123456Z", 123456},
      {"2026-01-01T00:00:00.123456000Z", 123456},
      {"2026-01-01T00:00:00.123456789Z", 123457},
      {"2026-01-01T00:00:00.123456789012Z", 123457},
      {"2026-01-01T00:00:00.999999999Z", 1000000},
  };
  for (const auto &test_case : cases) {
    SCOPED_TRACE(test_case.timestamp);
    const auto parsed = detail::parse_kubernetes_lease_timestamp(test_case.timestamp);
    ASSERT_TRUE(parsed.has_value());
    EXPECT_EQ(*parsed, midnight + std::chrono::microseconds(test_case.expected_microseconds));
  }
  EXPECT_TRUE(detail::parse_kubernetes_lease_timestamp("2024-02-29T23:59:59Z").has_value());
}

TEST(KubernetesLeaseTimestampTest, RejectsMalformedOrOutOfRangeUtcTimestamps) {
  constexpr std::string_view malformed[] = {
      "2026-01-01T00:00:00XZ",
      "2026-01-01T00:00:00.12XZ",
      "2026-01-01T00:00:00.123garbageZ",
      "2026-01-01T00:00:00.Z",
      "2026-01-01T00:00:00Zgarbage",
      "2026-01-01T00:00:00+00:00",
      "2026-02-29T00:00:00Z",
      "2026-04-31T00:00:00Z",
      "2026-13-01T00:00:00Z",
      "2026-01-01T24:00:00Z",
      "2026-01-01T00:60:00Z",
      "2026-01-01T00:00:60Z",
      "2026-1-01T00:00:00Z",
      "2026-01-01 00:00:00Z",
      "9999-01-01T00:00:00Z",
  };
  for (const auto value : malformed) {
    SCOPED_TRACE(value);
    EXPECT_FALSE(detail::parse_kubernetes_lease_timestamp(value).has_value());
  }
}

TEST(KubernetesLeaseTimestampTest, DoesNotTreatUnknownHeldLeaseAsExpired) {
  const auto now = std::chrono::system_clock::time_point(std::chrono::seconds(1767225600));
  nlohmann::json spec = {{"holderIdentity", "another-pod"}, {"leaseDurationSeconds", 15}};
  EXPECT_FALSE(detail::inspect_kubernetes_lease_spec(spec, now).has_value());
  spec["renewTime"] = "2025-12-31T23:59:46Z";
  auto observation = detail::inspect_kubernetes_lease_spec(spec, now);
  ASSERT_TRUE(observation.has_value());
  EXPECT_FALSE(observation->expired);
  spec["renewTime"] = "2025-12-31T23:59:45Z";
  observation = detail::inspect_kubernetes_lease_spec(spec, now);
  ASSERT_TRUE(observation.has_value());
  EXPECT_TRUE(observation->expired);
}

TEST(KubernetesLeaseTimestampTest, RejectsMalformedPresentRenewTimeForUnheldLease) {
  const auto now = std::chrono::system_clock::time_point(std::chrono::seconds(1767225600));
  nlohmann::json spec = {{"holderIdentity", ""}, {"leaseDurationSeconds", 15}};
  for (const nlohmann::json &malformed : {
           nlohmann::json("2026-01-01T00:00:00XZ"),
           nlohmann::json("2026-02-29T00:00:00Z"),
           nlohmann::json(""), nlohmann::json(nullptr), nlohmann::json(123)}) {
    spec["renewTime"] = malformed;
    SCOPED_TRACE(spec.dump());
    EXPECT_FALSE(detail::inspect_kubernetes_lease_spec(spec, now).has_value());
  }
}

TEST(KubernetesLeaseTimestampTest, AcquiresUnheldLeaseWhenRenewTimeIsAbsent) {
  const auto now = std::chrono::system_clock::time_point(std::chrono::seconds(1767225600));
  const nlohmann::json spec = {{"holderIdentity", ""}};
  const auto observation = detail::inspect_kubernetes_lease_spec(spec, now);
  ASSERT_TRUE(observation.has_value());
  EXPECT_TRUE(observation->expired);
  EXPECT_TRUE(observation->holder_identity.empty());
  EXPECT_EQ(observation->duration_seconds, 15);
}

TEST(KubernetesLeaseTimestampTest, RejectsExplicitInvalidLeaseDurationWithoutThrowing) {
  const auto now = std::chrono::system_clock::time_point(std::chrono::seconds(1767225600));
  nlohmann::json spec = {{"holderIdentity", ""}};
  for (const nlohmann::json &malformed : {
           nlohmann::json(0), nlohmann::json(-1), nlohmann::json(15.0),
           nlohmann::json("15"), nlohmann::json(true), nlohmann::json(nullptr),
           nlohmann::json(static_cast<std::int64_t>(std::numeric_limits<int>::max()) + 1),
           nlohmann::json(std::numeric_limits<std::uint64_t>::max())}) {
    spec["leaseDurationSeconds"] = malformed;
    SCOPED_TRACE(spec.dump());
    EXPECT_FALSE(detail::inspect_kubernetes_lease_spec(spec, now).has_value());
  }
  spec["leaseDurationSeconds"] = 15;
  const auto valid = detail::inspect_kubernetes_lease_spec(spec, now);
  ASSERT_TRUE(valid.has_value());
  EXPECT_EQ(valid->duration_seconds, 15);
  EXPECT_TRUE(valid->expired);
}

TEST(KubernetesLeaseTimestampTest, RejectsMalformedExplicitHolderIdentity) {
  const auto now = std::chrono::system_clock::time_point(std::chrono::seconds(1767225600));
  const nlohmann::json spec = {{"holderIdentity", 19}, {"leaseDurationSeconds", 15}};
  EXPECT_FALSE(detail::inspect_kubernetes_lease_spec(spec, now).has_value());
}

TEST(RdkafkaRuntimeAdapterTest, GivesRecoveryMetadataQueriesAtLeastOneSecond) {
  EXPECT_EQ(detail::kafka_offset_query_timeout(std::chrono::milliseconds(100)),
            std::chrono::seconds(1));
  EXPECT_EQ(detail::kafka_offset_query_timeout(std::chrono::milliseconds(1500)),
            std::chrono::milliseconds(1500));
}

TEST(RdkafkaRuntimeAdapterTest,
     RejectsConsumerTimeoutBeyondLibrdkafkaIntRange) {
  const auto timeout =
      std::chrono::milliseconds(std::numeric_limits<int>::max()) +
      std::chrono::milliseconds(1);

  EXPECT_THROW(RdkafkaDirectPartitionKafkaConsumer(
                   "127.0.0.1:1", "timeout-boundary-test", timeout),
               std::invalid_argument);
}

TEST(RdkafkaRuntimeAdapterTest,
     RejectsPublisherFlushTimeoutBeyondLibrdkafkaIntRange) {
  const auto timeout =
      std::chrono::milliseconds(std::numeric_limits<int>::max()) +
      std::chrono::milliseconds(1);

  EXPECT_THROW(
      RdkafkaMatchingEventPublisher("127.0.0.1:1", "matching.events", timeout),
      std::invalid_argument);
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
