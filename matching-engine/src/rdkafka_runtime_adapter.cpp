#include "simplematch/matching/runtime/rdkafka_runtime_adapter.hpp"

#include <chrono>
#include <cstddef>
#include <algorithm>
#include <stdexcept>
#include <utility>
#include <vector>

#include <librdkafka/rdkafka.h>

namespace simplematch::matching {
namespace {

struct KafkaConfigurationDeleter {
  void operator()(rd_kafka_conf_t *configuration) const {
    if (configuration != nullptr) {
      rd_kafka_conf_destroy(configuration);
    }
  }
};

struct KafkaMessageDeleter {
  void operator()(rd_kafka_message_t *message) const {
    if (message != nullptr) {
      rd_kafka_message_destroy(message);
    }
  }
};

void set_required_configuration(
    rd_kafka_conf_t *configuration, const char *name, const std::string &value) {
  char error[512]{};
  if (rd_kafka_conf_set(configuration, name, value.c_str(), error, sizeof(error)) !=
      RD_KAFKA_CONF_OK) {
    throw std::invalid_argument(std::string("invalid Kafka configuration ") + name + ": " + error);
  }
}

std::string kafka_error(rd_kafka_resp_err_t error) {
  return std::string(rd_kafka_err2name(error)) + ": " + rd_kafka_err2str(error);
}

std::int32_t checked_partition(std::int32_t partition) {
  if (partition < 0 || partition >= 15) {
    throw std::invalid_argument("Matching Kafka partition must be between 0 and 14");
  }
  return partition;
}

std::int64_t checked_offset(std::int64_t offset) {
  if (offset < 0) {
    throw std::invalid_argument("Matching Kafka offset must not be negative");
  }
  return offset;
}

} // namespace

namespace detail {

std::chrono::milliseconds kafka_offset_query_timeout(std::chrono::milliseconds poll_timeout) {
  return std::max(poll_timeout, std::chrono::milliseconds(1000));
}

} // namespace detail

struct RdkafkaDirectPartitionKafkaConsumer::Implementation {
  rd_kafka_t *consumer{};
};

RdkafkaDirectPartitionKafkaConsumer::RdkafkaDirectPartitionKafkaConsumer(
    std::string bootstrap_servers,
    std::string consumer_group,
    std::chrono::milliseconds poll_timeout)
    : implementation_(std::make_unique<Implementation>()), poll_timeout_(poll_timeout) {
  if (bootstrap_servers.empty() || consumer_group.empty() || poll_timeout_ <= std::chrono::milliseconds::zero()) {
    throw std::invalid_argument("Kafka consumer requires brokers, group, and positive poll timeout");
  }
  std::unique_ptr<rd_kafka_conf_t, KafkaConfigurationDeleter> configuration(
      rd_kafka_conf_new());
  set_required_configuration(configuration.get(), "bootstrap.servers", bootstrap_servers);
  set_required_configuration(configuration.get(), "group.id", consumer_group);
  set_required_configuration(configuration.get(), "enable.auto.commit", "false");
  set_required_configuration(configuration.get(), "enable.auto.offset.store", "false");
  set_required_configuration(configuration.get(), "auto.offset.reset", "earliest");
  set_required_configuration(configuration.get(), "allow.auto.create.topics", "false");

  char error[512]{};
  implementation_->consumer = rd_kafka_new(
      RD_KAFKA_CONSUMER, configuration.release(), error, sizeof(error));
  if (implementation_->consumer == nullptr) {
    throw std::runtime_error(std::string("unable to create Kafka consumer: ") + error);
  }
  const auto poll_error = rd_kafka_poll_set_consumer(implementation_->consumer);
  if (poll_error != RD_KAFKA_RESP_ERR_NO_ERROR) {
    rd_kafka_destroy(implementation_->consumer);
    implementation_->consumer = nullptr;
    throw std::runtime_error("unable to initialize Kafka consumer polling: " + kafka_error(poll_error));
  }
}

RdkafkaDirectPartitionKafkaConsumer::~RdkafkaDirectPartitionKafkaConsumer() {
  if (implementation_ != nullptr && implementation_->consumer != nullptr) {
    rd_kafka_consumer_close(implementation_->consumer);
    rd_kafka_destroy(implementation_->consumer);
  }
}

void RdkafkaDirectPartitionKafkaConsumer::assign(
    const DirectKafkaPartitionAssignment &assignment) {
  checked_partition(assignment.partition);
  if (assignment.topic.empty()) {
    throw std::invalid_argument("Matching Kafka assignment topic must not be empty");
  }
  rd_kafka_topic_partition_list_t *partitions = rd_kafka_topic_partition_list_new(1);
  auto *entry = rd_kafka_topic_partition_list_add(
      partitions, assignment.topic.c_str(), assignment.partition);
  entry->offset = RD_KAFKA_OFFSET_STORED;
  const auto error = rd_kafka_assign(implementation_->consumer, partitions);
  rd_kafka_topic_partition_list_destroy(partitions);
  if (error != RD_KAFKA_RESP_ERR_NO_ERROR) {
    throw std::runtime_error("unable to assign Matching Kafka partition: " + kafka_error(error));
  }
  assignment_ = assignment;
}

std::optional<AssignedCommandRecord> RdkafkaDirectPartitionKafkaConsumer::poll() {
  const auto timeout = poll_timeout_.count();
  rd_kafka_message_t *message = rd_kafka_consumer_poll(implementation_->consumer, timeout);
  if (message == nullptr) {
    return std::nullopt;
  }
  const std::unique_ptr<rd_kafka_message_t, KafkaMessageDeleter> cleanup(message);
  if (message->err == RD_KAFKA_RESP_ERR__TIMED_OUT ||
      message->err == RD_KAFKA_RESP_ERR__PARTITION_EOF) {
    return std::nullopt;
  }
  if (message->err != RD_KAFKA_RESP_ERR_NO_ERROR || message->rkt == nullptr ||
      message->payload == nullptr) {
    const auto error = message->err == RD_KAFKA_RESP_ERR_NO_ERROR
                           ? RD_KAFKA_RESP_ERR__FAIL
                           : message->err;
    throw std::runtime_error("Kafka consumer poll failed: " + kafka_error(error));
  }
  AssignedCommandRecord record{
      {rd_kafka_topic_name(message->rkt), message->partition},
      message->offset,
      message->key == nullptr
          ? std::string{}
          : std::string(static_cast<const char *>(message->key), message->key_len),
      std::string(static_cast<const char *>(message->payload), message->len)};
  return record;
}

void RdkafkaDirectPartitionKafkaConsumer::commit_synchronously(std::int64_t next_offset) {
  if (!assignment_.has_value()) {
    throw std::logic_error("cannot commit a Matching Kafka partition before assign");
  }
  rd_kafka_topic_partition_list_t *partitions = rd_kafka_topic_partition_list_new(1);
  auto *entry = rd_kafka_topic_partition_list_add(
      partitions,
      assignment_->topic.c_str(),
      checked_partition(assignment_->partition));
  entry->offset = checked_offset(next_offset);
  const auto error = rd_kafka_commit(implementation_->consumer, partitions, 1);
  rd_kafka_topic_partition_list_destroy(partitions);
  if (error != RD_KAFKA_RESP_ERR_NO_ERROR) {
    throw std::runtime_error("unable to commit Matching Kafka offset: " + kafka_error(error));
  }
}

DirectKafkaPartitionOffsets RdkafkaDirectPartitionKafkaConsumer::offsets() {
  if (!assignment_.has_value()) {
    throw std::logic_error("cannot query Matching Kafka offsets before assign");
  }
  std::int64_t earliest = -1;
  std::int64_t end = -1;
  const int query_timeout_millis = static_cast<int>(
      detail::kafka_offset_query_timeout(poll_timeout_).count());
  const auto watermark_error = rd_kafka_query_watermark_offsets(
      implementation_->consumer,
      assignment_->topic.c_str(),
      checked_partition(assignment_->partition),
      &earliest,
      &end,
      query_timeout_millis);
  if (watermark_error != RD_KAFKA_RESP_ERR_NO_ERROR) {
    throw std::runtime_error(
        "unable to query Matching Kafka retained offsets: " + kafka_error(watermark_error));
  }

  rd_kafka_topic_partition_list_t *partitions = rd_kafka_topic_partition_list_new(1);
  auto *entry = rd_kafka_topic_partition_list_add(
      partitions, assignment_->topic.c_str(), checked_partition(assignment_->partition));
  entry->offset = RD_KAFKA_OFFSET_INVALID;
  const auto committed_error = rd_kafka_committed(
      implementation_->consumer, partitions, query_timeout_millis);
  if (committed_error != RD_KAFKA_RESP_ERR_NO_ERROR) {
    rd_kafka_topic_partition_list_destroy(partitions);
    throw std::runtime_error(
        "unable to query Matching Kafka committed offset: " + kafka_error(committed_error));
  }
  std::optional<std::int64_t> committed;
  if (entry->err != RD_KAFKA_RESP_ERR_NO_ERROR && entry->err != RD_KAFKA_RESP_ERR__NO_OFFSET) {
    const auto entry_error = entry->err;
    rd_kafka_topic_partition_list_destroy(partitions);
    throw std::runtime_error(
        "unable to read Matching Kafka committed offset: " + kafka_error(entry_error));
  }
  if (entry->offset != RD_KAFKA_OFFSET_INVALID && entry->offset >= 0) {
    committed = entry->offset;
  }
  rd_kafka_topic_partition_list_destroy(partitions);
  return {earliest, end, committed};
}

std::vector<AssignedCommandRecord> RdkafkaDirectPartitionKafkaConsumer::read_retained(
    std::int64_t first_offset, std::int64_t end_offset) {
  if (first_offset < 0 || end_offset < first_offset) {
    throw std::invalid_argument("invalid Matching Kafka retained range");
  }
  const auto range = offsets();
  if (first_offset < range.earliest_retained_offset || end_offset > range.end_offset) {
    throw std::runtime_error("requested Matching Kafka retained range is no longer available");
  }
  seek(first_offset);
  std::vector<AssignedCommandRecord> records;
  records.reserve(static_cast<std::size_t>(end_offset - first_offset));
  std::int64_t next_offset = first_offset;
  std::size_t idle_polls = 0;
  while (next_offset < end_offset) {
    const auto record = poll();
    if (!record.has_value()) {
      if (++idle_polls >= 1000) {
        throw std::runtime_error("timed out reading Matching Kafka retained range");
      }
      continue;
    }
    idle_polls = 0;
    if (record->offset < next_offset) {
      continue;
    }
    if (record->offset != next_offset) {
      throw std::runtime_error("gap while reading Matching Kafka retained range");
    }
    records.push_back(*record);
    ++next_offset;
  }
  return records;
}

void RdkafkaDirectPartitionKafkaConsumer::seek(std::int64_t next_offset) {
  if (!assignment_.has_value()) {
    throw std::logic_error("cannot seek Matching Kafka before assign");
  }
  rd_kafka_topic_partition_list_t *partitions = rd_kafka_topic_partition_list_new(1);
  auto *entry = rd_kafka_topic_partition_list_add(
      partitions, assignment_->topic.c_str(), checked_partition(assignment_->partition));
  entry->offset = checked_offset(next_offset);
  rd_kafka_error_t *seek_error = rd_kafka_seek_partitions(
      implementation_->consumer, partitions, static_cast<int>(poll_timeout_.count()));
  rd_kafka_topic_partition_list_destroy(partitions);
  if (seek_error != nullptr) {
    const auto error = rd_kafka_error_code(seek_error);
    const std::string message = rd_kafka_error_string(seek_error);
    rd_kafka_error_destroy(seek_error);
    throw std::runtime_error("unable to seek Matching Kafka partition: " +
                             kafka_error(error) + " (" + message + ")");
  }
}

struct DeliveryState {
  rd_kafka_resp_err_t last_delivery_error{RD_KAFKA_RESP_ERR_NO_ERROR};
};

void delivery_report(rd_kafka_t *, const rd_kafka_message_t *message, void *opaque) {
  auto *state = static_cast<DeliveryState *>(opaque);
  state->last_delivery_error = message->err;
}

struct RdkafkaMatchingEventPublisher::Implementation {
  rd_kafka_t *producer{};
  DeliveryState delivery_state;
};

RdkafkaMatchingEventPublisher::RdkafkaMatchingEventPublisher(
    std::string bootstrap_servers,
    std::string topic,
    std::chrono::milliseconds flush_timeout)
    : implementation_(std::make_unique<Implementation>()),
      topic_(std::move(topic)),
      flush_timeout_(flush_timeout) {
  if (bootstrap_servers.empty() || topic_.empty() ||
      flush_timeout_ <= std::chrono::milliseconds::zero()) {
    throw std::invalid_argument("Kafka publisher requires brokers, topic, and positive flush timeout");
  }
  std::unique_ptr<rd_kafka_conf_t, KafkaConfigurationDeleter> configuration(
      rd_kafka_conf_new());
  rd_kafka_conf_set_dr_msg_cb(configuration.get(), delivery_report);
  rd_kafka_conf_set_opaque(configuration.get(), &implementation_->delivery_state);
  set_required_configuration(configuration.get(), "bootstrap.servers", bootstrap_servers);
  set_required_configuration(configuration.get(), "enable.idempotence", "true");
  set_required_configuration(configuration.get(), "acks", "all");
  set_required_configuration(configuration.get(), "allow.auto.create.topics", "false");

  char error[512]{};
  implementation_->producer = rd_kafka_new(
      RD_KAFKA_PRODUCER, configuration.release(), error, sizeof(error));
  if (implementation_->producer == nullptr) {
    throw std::runtime_error(std::string("unable to create Kafka producer: ") + error);
  }
}

RdkafkaMatchingEventPublisher::~RdkafkaMatchingEventPublisher() {
  if (implementation_ != nullptr && implementation_->producer != nullptr) {
    rd_kafka_flush(implementation_->producer, flush_timeout_.count());
    rd_kafka_destroy(implementation_->producer);
  }
}

bool RdkafkaMatchingEventPublisher::publish(const MatchingEventRecord &record) {
  checked_partition(record.partition_id);
  if (record.key.empty() || record.value.empty()) {
    throw std::invalid_argument("Matching Event publication requires a key and value");
  }
  implementation_->delivery_state.last_delivery_error = RD_KAFKA_RESP_ERR_NO_ERROR;
  const auto error = rd_kafka_producev(
      implementation_->producer,
      RD_KAFKA_V_TOPIC(topic_.c_str()),
      RD_KAFKA_V_PARTITION(record.partition_id),
      RD_KAFKA_V_KEY(record.key.data(), record.key.size()),
      RD_KAFKA_V_VALUE(const_cast<char *>(record.value.data()), record.value.size()),
      RD_KAFKA_V_MSGFLAGS(RD_KAFKA_MSG_F_COPY),
      RD_KAFKA_V_END);
  if (error != RD_KAFKA_RESP_ERR_NO_ERROR) {
    return false;
  }
  if (rd_kafka_flush(implementation_->producer, flush_timeout_.count()) !=
      RD_KAFKA_RESP_ERR_NO_ERROR) {
    return false;
  }
  return implementation_->delivery_state.last_delivery_error == RD_KAFKA_RESP_ERR_NO_ERROR;
}

} // namespace simplematch::matching
