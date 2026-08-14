#pragma once

#include "simplematch/matching/runtime/matching_partition_runtime_driver.hpp"

#include <chrono>
#include <cstdint>
#include <memory>
#include <cstddef>
#include <optional>
#include <string>

namespace simplematch::matching {

namespace detail {

/** Keeps Kafka recovery metadata requests from inheriting the hot-path poll timeout. */
std::chrono::milliseconds kafka_offset_query_timeout(std::chrono::milliseconds poll_timeout);

} // namespace detail

/** Direct-assignment Kafka consumer used by one Matching partition process. */
class RdkafkaDirectPartitionKafkaConsumer final : public DirectPartitionKafkaConsumer {
public:
  RdkafkaDirectPartitionKafkaConsumer(
      std::string bootstrap_servers,
      std::string consumer_group,
      std::chrono::milliseconds poll_timeout);
  ~RdkafkaDirectPartitionKafkaConsumer() override;

  RdkafkaDirectPartitionKafkaConsumer(const RdkafkaDirectPartitionKafkaConsumer &) = delete;
  RdkafkaDirectPartitionKafkaConsumer &operator=(const RdkafkaDirectPartitionKafkaConsumer &) = delete;

  void assign(const DirectKafkaPartitionAssignment &assignment) override;
  [[nodiscard]] std::optional<AssignedCommandRecord> poll() override;
  void commit_synchronously(std::int64_t next_offset) override;
  [[nodiscard]] DirectKafkaPartitionOffsets offsets() override;
  [[nodiscard]] std::vector<AssignedCommandRecord> read_retained_batch(
      std::int64_t first_offset,
      std::int64_t end_offset,
      std::size_t maximum_records) override;
  void seek(std::int64_t next_offset) override;

private:
  struct Implementation;

  std::unique_ptr<Implementation> implementation_;
  std::chrono::milliseconds poll_timeout_;
  std::optional<DirectKafkaPartitionAssignment> assignment_;
};

/** Idempotent Kafka producer for the deterministic matching.events record value. */
class RdkafkaMatchingEventPublisher final : public MatchingEventPublisher {
public:
  RdkafkaMatchingEventPublisher(
      std::string bootstrap_servers,
      std::string topic,
      std::chrono::milliseconds flush_timeout,
      std::size_t maximum_in_flight = 1024);
  ~RdkafkaMatchingEventPublisher() override;

  RdkafkaMatchingEventPublisher(const RdkafkaMatchingEventPublisher &) = delete;
  RdkafkaMatchingEventPublisher &operator=(const RdkafkaMatchingEventPublisher &) = delete;

  [[nodiscard]] bool publish(const MatchingEventRecord &record) override;
  [[nodiscard]] bool supports_async() const noexcept override { return true; }
  [[nodiscard]] MatchingPublicationSubmitResult submit_async(
      const MatchingEventRecord &record, std::uint64_t publication_id) override;
  void service() override;
  [[nodiscard]] std::optional<MatchingPublicationCompletion> next_completion() override;

private:
  struct Implementation;

  std::unique_ptr<Implementation> implementation_;
  std::string topic_;
  std::chrono::milliseconds flush_timeout_;
  std::size_t maximum_in_flight_;
};

} // namespace simplematch::matching
