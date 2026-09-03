#include <charconv>
#include <cstddef>
#include <cstdint>
#include <iomanip>
#include <iostream>
#include <iterator>
#include <sstream>
#include <stdexcept>
#include <string>
#include <string_view>
#include <system_error>

#include <librdkafka/rdkafka.h>

#include "matching_runtime_v1.pb.h"
#include "simplematch/matching/core/deterministic_matching_core.hpp"

namespace {

constexpr std::string_view kTradingDay = "2026-08-11";
constexpr std::string_view kTradingSession = "2026-08-11-regular";
constexpr std::string_view kArtifactSha256 =
    "7cd06c51691bcde248e606ed1adfaddc4bd10ece582a6803fd2f04155a032943";
constexpr std::string_view kRoutingVersion = "stable-least-loaded-v1";
constexpr std::string_view kImageDigest =
    "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

struct DeliveryState {
  bool failed{};
  rd_kafka_resp_err_t error{RD_KAFKA_RESP_ERR_NO_ERROR};
};

struct BarrierBootstrapConfiguration {
  std::string_view trading_day;
  std::string_view trading_session;
  std::string_view artifact_sha256;
  std::string_view routing_version;
  std::string_view image_digest;
};

struct BarrierValidationConfiguration {
  std::string_view trading_day;
  std::string_view trading_session;
  std::string_view artifact_sha256;
  std::string_view routing_version;
  std::string_view image_digest;
  std::int32_t partition;
  std::string_view key;
};

[[noreturn]] void validation_failure(std::string_view reason) {
  throw std::invalid_argument(std::string("Open Barrier validation failed: ") +
                              std::string(reason));
}

int hex_value(unsigned char value) {
  if (value >= '0' && value <= '9') {
    return value - '0';
  }
  if (value >= 'a' && value <= 'f') {
    return value - 'a' + 10;
  }
  if (value >= 'A' && value <= 'F') {
    return value - 'A' + 10;
  }
  return -1;
}

bool canonical_sha256(std::string_view value) {
  if (value.size() != 64) {
    return false;
  }
  for (const unsigned char character : value) {
    if (!((character >= '0' && character <= '9') ||
          (character >= 'a' && character <= 'f'))) {
      return false;
    }
  }
  return true;
}

bool canonical_image_digest(std::string_view value) {
  return value.starts_with("sha256:") && canonical_sha256(value.substr(7));
}

std::string decode_hex(std::string_view encoded) {
  if (encoded.empty() || encoded.size() % 2 != 0) {
    validation_failure("payload is not a non-empty hexadecimal byte sequence");
  }
  std::string decoded;
  decoded.resize(encoded.size() / 2);
  for (std::size_t index = 0; index < encoded.size(); index += 2) {
    const int high = hex_value(static_cast<unsigned char>(encoded[index]));
    const int low = hex_value(static_cast<unsigned char>(encoded[index + 1]));
    if (high < 0 || low < 0) {
      validation_failure("payload contains a non-hexadecimal byte");
    }
    decoded[index / 2] = static_cast<char>((high << 4) | low);
  }
  return decoded;
}

std::int32_t parse_partition(std::string_view value) {
  if (value.empty()) {
    validation_failure("partition is outside the matching.commands range");
  }
  std::int32_t partition{};
  const auto result =
      std::from_chars(value.data(), value.data() + value.size(), partition);
  if (result.ec != std::errc{} || result.ptr != value.data() + value.size() ||
      partition < 0 || partition >= 15) {
    validation_failure("partition is outside the matching.commands range");
  }
  return partition;
}

void validate_open_barrier(std::istream &payload_stream,
                           const BarrierValidationConfiguration &configuration) {
  if (configuration.trading_day.empty() || configuration.trading_session.empty() ||
      !canonical_sha256(configuration.artifact_sha256) ||
      configuration.routing_version.empty() ||
      !canonical_image_digest(configuration.image_digest) ||
      configuration.partition < 0 || configuration.partition >= 15 ||
      configuration.key.empty()) {
    validation_failure("current session or artifact identity is incomplete");
  }
  if (!simplematch::matching::CoreUuid::parse(configuration.key).has_value()) {
    validation_failure("Kafka key is not a valid command identity");
  }

  const std::string encoded_payload(
      std::istreambuf_iterator<char>(payload_stream),
      std::istreambuf_iterator<char>());
  if (payload_stream.bad()) {
    validation_failure("payload could not be read");
  }
  const std::string payload = decode_hex(encoded_payload);
  simplematch::matching::runtime::v1::MatchingCommand command;
  if (!command.ParseFromString(payload) || !command.has_header() ||
      command.command_case() !=
          simplematch::matching::runtime::v1::MatchingCommand::kOpenBarrier) {
    validation_failure("payload is not an Open Barrier protobuf");
  }

  const auto &header = command.header();
  if (!header.has_artifact_identity()) {
    validation_failure("Open Barrier header has no artifact identity");
  }
  const auto &artifact = header.artifact_identity();
  const auto &barrier = command.open_barrier();
  if (header.schema_version() != 1 ||
      std::string_view(header.command_id()) != configuration.key ||
      std::string_view(header.trading_session_id()) != configuration.trading_session ||
      header.partition_id() != configuration.partition ||
      std::string_view(artifact.trading_day()) != configuration.trading_day ||
      std::string_view(artifact.content_sha256()) != configuration.artifact_sha256 ||
      std::string_view(header.routing_algorithm_version()) !=
          configuration.routing_version) {
    validation_failure("Open Barrier header does not match the current run");
  }
  if (barrier.expected_partition_count() != 15 || barrier.event_schema_version() != 1 ||
      barrier.event_identity_version() != 1 ||
      barrier.matching_image_digest() != configuration.image_digest) {
    validation_failure("Open Barrier fields do not match the current run");
  }
}

void delivery_report(rd_kafka_t *, const rd_kafka_message_t *message,
                     void *opaque) {
  auto *state = static_cast<DeliveryState *>(opaque);
  if (message->err != RD_KAFKA_RESP_ERR_NO_ERROR) {
    state->failed = true;
    state->error = message->err;
  }
}

void set_configuration(rd_kafka_conf_t *configuration, const char *name,
                       const char *value) {
  char error[512]{};
  if (rd_kafka_conf_set(configuration, name, value, error, sizeof(error)) !=
      RD_KAFKA_CONF_OK) {
    throw std::invalid_argument(std::string("invalid Kafka configuration ") +
                                name + ": " + error);
  }
}

simplematch::matching::runtime::v1::MatchingCommand
base_command(std::string_view command_id) {
  simplematch::matching::runtime::v1::MatchingCommand command;
  auto *header = command.mutable_header();
  header->set_schema_version(1);
  header->set_command_id(std::string(command_id));
  header->set_trading_session_id(std::string(kTradingSession));
  header->set_partition_id(0);
  header->mutable_artifact_identity()->set_trading_day(
      std::string(kTradingDay));
  header->mutable_artifact_identity()->set_content_sha256(
      std::string(kArtifactSha256));
  header->set_routing_algorithm_version(std::string(kRoutingVersion));
  return command;
}

simplematch::matching::runtime::v1::MatchingCommand open_barrier() {
  auto command = base_command("0198a001-0000-7000-8000-000000000001");
  command.mutable_open_barrier()->set_expected_partition_count(15);
  command.mutable_open_barrier()->set_event_schema_version(1);
  command.mutable_open_barrier()->set_event_identity_version(1);
  command.mutable_open_barrier()->set_matching_image_digest(
      std::string(kImageDigest));
  return command;
}

std::string barrier_command_id(std::int32_t partition) {
  std::ostringstream command_id;
  command_id << "0198a001-0000-7000-8000-" << std::setw(12) << std::setfill('0')
             << partition + 1;
  return command_id.str();
}

simplematch::matching::runtime::v1::MatchingCommand
configured_open_barrier(std::int32_t partition,
                        const BarrierBootstrapConfiguration &configuration) {
  simplematch::matching::runtime::v1::MatchingCommand command;
  auto *header = command.mutable_header();
  header->set_schema_version(1);
  header->set_command_id(barrier_command_id(partition));
  header->set_trading_session_id(std::string(configuration.trading_session));
  header->set_partition_id(partition);
  header->mutable_artifact_identity()->set_trading_day(
      std::string(configuration.trading_day));
  header->mutable_artifact_identity()->set_content_sha256(
      std::string(configuration.artifact_sha256));
  header->set_routing_algorithm_version(
      std::string(configuration.routing_version));
  auto *barrier = command.mutable_open_barrier();
  barrier->set_expected_partition_count(15);
  barrier->set_event_schema_version(1);
  barrier->set_event_identity_version(1);
  barrier->set_matching_image_digest(std::string(configuration.image_digest));
  return command;
}

simplematch::matching::runtime::v1::MatchingCommand
limit_order(std::string_view command_id, std::string_view order_id,
            std::string_view account_id, simplematch::common::v2::Side side) {
  auto command = base_command(command_id);
  auto *order = command.mutable_new_order();
  order->set_order_id(std::string(order_id));
  order->set_account_id(std::string(account_id));
  order->mutable_instrument()->set_venue_mic("XTAI");
  order->mutable_instrument()->set_symbol("2330");
  order->set_side(side);
  order->set_quantity_shares(100);
  order->set_limit_price_units(1'000'000);
  order->set_order_type(simplematch::common::v2::ORDER_TYPE_LIMIT);
  order->set_time_in_force(simplematch::common::v2::TIME_IN_FORCE_ROD);
  return command;
}

void publish(
    rd_kafka_t *producer, std::string_view topic,
    const simplematch::matching::runtime::v1::MatchingCommand &command) {
  const std::string key = command.header().command_id();
  const std::string value = command.SerializeAsString();
  const auto error = rd_kafka_producev(
      producer, RD_KAFKA_V_TOPIC(topic.data()), RD_KAFKA_V_PARTITION(0),
      RD_KAFKA_V_KEY(key.data(), key.size()),
      RD_KAFKA_V_VALUE(const_cast<char *>(value.data()), value.size()),
      RD_KAFKA_V_MSGFLAGS(RD_KAFKA_MSG_F_COPY), RD_KAFKA_V_END);
  if (error != RD_KAFKA_RESP_ERR_NO_ERROR) {
    throw std::runtime_error(
        std::string("fixture command publication failed: ") +
        rd_kafka_err2str(error));
  }
}

void publish_to_partition(
    rd_kafka_t *producer, std::string_view topic, std::int32_t partition,
    const simplematch::matching::runtime::v1::MatchingCommand &command) {
  const std::string key = command.header().command_id();
  const std::string value = command.SerializeAsString();
  const auto error = rd_kafka_producev(
      producer, RD_KAFKA_V_TOPIC(topic.data()), RD_KAFKA_V_PARTITION(partition),
      RD_KAFKA_V_KEY(key.data(), key.size()),
      RD_KAFKA_V_VALUE(const_cast<char *>(value.data()), value.size()),
      RD_KAFKA_V_MSGFLAGS(RD_KAFKA_MSG_F_COPY), RD_KAFKA_V_END);
  if (error != RD_KAFKA_RESP_ERR_NO_ERROR) {
    throw std::runtime_error(
        std::string("barrier command publication failed: ") +
        rd_kafka_err2str(error));
  }
}

void publish_fixture(std::string_view brokers, std::string_view topic) {
  rd_kafka_conf_t *configuration = rd_kafka_conf_new();
  DeliveryState delivery_state;
  rd_kafka_conf_set_dr_msg_cb(configuration, delivery_report);
  rd_kafka_conf_set_opaque(configuration, &delivery_state);
  set_configuration(configuration, "bootstrap.servers",
                    std::string(brokers).c_str());
  set_configuration(configuration, "enable.idempotence", "true");
  set_configuration(configuration, "acks", "all");
  set_configuration(configuration, "allow.auto.create.topics", "false");

  char error[512]{};
  rd_kafka_t *producer =
      rd_kafka_new(RD_KAFKA_PRODUCER, configuration, error, sizeof(error));
  if (producer == nullptr) {
    throw std::runtime_error(
        std::string("unable to create fixture producer: ") + error);
  }
  try {
    publish(producer, topic, open_barrier());
    publish(producer, topic,
            limit_order("0198a001-0000-7000-8000-000000000002",
                        "0198a001-0000-7000-8000-000000000012",
                        "0198a001-0000-7000-8000-0000000000aa",
                        simplematch::common::v2::SIDE_SELL));
    publish(producer, topic,
            limit_order("0198a001-0000-7000-8000-000000000003",
                        "0198a001-0000-7000-8000-000000000013",
                        "0198a001-0000-7000-8000-0000000000bb",
                        simplematch::common::v2::SIDE_BUY));
    const auto flush_error = rd_kafka_flush(producer, 10'000);
    if (flush_error != RD_KAFKA_RESP_ERR_NO_ERROR || delivery_state.failed) {
      const auto report_error =
          delivery_state.failed ? delivery_state.error : flush_error;
      throw std::runtime_error(
          std::string("fixture command delivery failed: ") +
          rd_kafka_err2str(report_error));
    }
  } catch (...) {
    rd_kafka_destroy(producer);
    throw;
  }
  rd_kafka_destroy(producer);
}

void publish_open_barriers(std::string_view brokers, std::string_view topic,
                           const BarrierBootstrapConfiguration &configuration) {
  rd_kafka_conf_t *producer_configuration = rd_kafka_conf_new();
  DeliveryState delivery_state;
  rd_kafka_conf_set_dr_msg_cb(producer_configuration, delivery_report);
  rd_kafka_conf_set_opaque(producer_configuration, &delivery_state);
  set_configuration(producer_configuration, "bootstrap.servers",
                    std::string(brokers).c_str());
  set_configuration(producer_configuration, "enable.idempotence", "true");
  set_configuration(producer_configuration, "acks", "all");
  set_configuration(producer_configuration, "allow.auto.create.topics",
                    "false");

  char error[512]{};
  rd_kafka_t *producer = rd_kafka_new(RD_KAFKA_PRODUCER, producer_configuration,
                                      error, sizeof(error));
  if (producer == nullptr) {
    throw std::runtime_error(
        std::string("unable to create barrier producer: ") + error);
  }
  try {
    for (std::int32_t partition = 0; partition < 15; ++partition) {
      publish_to_partition(producer, topic, partition,
                           configured_open_barrier(partition, configuration));
    }
    const auto flush_error = rd_kafka_flush(producer, 10'000);
    if (flush_error != RD_KAFKA_RESP_ERR_NO_ERROR || delivery_state.failed) {
      const auto report_error =
          delivery_state.failed ? delivery_state.error : flush_error;
      throw std::runtime_error(std::string("barrier delivery failed: ") +
                               rd_kafka_err2str(report_error));
    }
  } catch (...) {
    rd_kafka_destroy(producer);
    throw;
  }
  rd_kafka_destroy(producer);
}

} // namespace

int main(int argc, char **argv) {
  if (argc == 9 && std::string_view(argv[1]) == "--validate-open-barrier") {
    try {
      validate_open_barrier(
          std::cin,
          BarrierValidationConfiguration{
              argv[2], argv[3], argv[4], argv[5], argv[6],
              parse_partition(argv[7]), argv[8]});
      return 0;
    } catch (const std::exception &failure) {
      std::cerr << failure.what() << '\n';
      return 1;
    }
  }
  if (argc != 3 && argc != 8) {
    std::cerr << "usage: simplematch-matching-kafka-fixture-publisher BROKERS "
                 "TOPIC [TRADING_DAY "
                 "TRADING_SESSION ARTIFACT_SHA256 ROUTING_VERSION "
                 "MATCHING_IMAGE_DIGEST]\n"
                 "       simplematch-matching-kafka-fixture-publisher "
                 "--validate-open-barrier TRADING_DAY TRADING_SESSION "
                 "ARTIFACT_SHA256 ROUTING_VERSION MATCHING_IMAGE_DIGEST "
                 "PARTITION KEY < PAYLOAD_HEX\n";
    return 2;
  }
  try {
    if (argc == 3) {
      publish_fixture(argv[1], argv[2]);
    } else {
      publish_open_barriers(argv[1], argv[2],
                            BarrierBootstrapConfiguration{
                                argv[3], argv[4], argv[5], argv[6], argv[7]});
    }
    return 0;
  } catch (const std::exception &failure) {
    std::cerr << failure.what() << '\n';
    return 1;
  }
}
