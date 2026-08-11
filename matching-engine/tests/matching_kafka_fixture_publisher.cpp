#include <cstddef>
#include <iostream>
#include <stdexcept>
#include <string>
#include <string_view>

#include <librdkafka/rdkafka.h>

#include "matching_runtime_v1.pb.h"

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

void delivery_report(rd_kafka_t *, const rd_kafka_message_t *message, void *opaque) {
  auto *state = static_cast<DeliveryState *>(opaque);
  if (message->err != RD_KAFKA_RESP_ERR_NO_ERROR) {
    state->failed = true;
    state->error = message->err;
  }
}

void set_configuration(rd_kafka_conf_t *configuration, const char *name, const char *value) {
  char error[512]{};
  if (rd_kafka_conf_set(configuration, name, value, error, sizeof(error)) != RD_KAFKA_CONF_OK) {
    throw std::invalid_argument(std::string("invalid Kafka configuration ") + name + ": " + error);
  }
}

simplematch::matching::runtime::v1::MatchingCommand base_command(std::string_view command_id) {
  simplematch::matching::runtime::v1::MatchingCommand command;
  auto *header = command.mutable_header();
  header->set_schema_version(1);
  header->set_command_id(std::string(command_id));
  header->set_trading_session_id(std::string(kTradingSession));
  header->set_partition_id(0);
  header->mutable_artifact_identity()->set_trading_day(std::string(kTradingDay));
  header->mutable_artifact_identity()->set_content_sha256(std::string(kArtifactSha256));
  header->set_routing_algorithm_version(std::string(kRoutingVersion));
  return command;
}

simplematch::matching::runtime::v1::MatchingCommand open_barrier() {
  auto command = base_command("0198a001-0000-7000-8000-000000000001");
  command.mutable_open_barrier()->set_expected_partition_count(15);
  command.mutable_open_barrier()->set_event_schema_version(1);
  command.mutable_open_barrier()->set_event_identity_version(1);
  command.mutable_open_barrier()->set_matching_image_digest(std::string(kImageDigest));
  return command;
}

simplematch::matching::runtime::v1::MatchingCommand limit_order(
    std::string_view command_id,
    std::string_view order_id,
    std::string_view account_id,
    simplematch::common::v2::Side side) {
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
    rd_kafka_t *producer,
    std::string_view topic,
    const simplematch::matching::runtime::v1::MatchingCommand &command) {
  const std::string key = command.header().command_id();
  const std::string value = command.SerializeAsString();
  const auto error = rd_kafka_producev(
      producer,
      RD_KAFKA_V_TOPIC(topic.data()),
      RD_KAFKA_V_PARTITION(0),
      RD_KAFKA_V_KEY(key.data(), key.size()),
      RD_KAFKA_V_VALUE(const_cast<char *>(value.data()), value.size()),
      RD_KAFKA_V_MSGFLAGS(RD_KAFKA_MSG_F_COPY),
      RD_KAFKA_V_END);
  if (error != RD_KAFKA_RESP_ERR_NO_ERROR) {
    throw std::runtime_error(
        std::string("fixture command publication failed: ") + rd_kafka_err2str(error));
  }
}

void publish_fixture(std::string_view brokers, std::string_view topic) {
  rd_kafka_conf_t *configuration = rd_kafka_conf_new();
  DeliveryState delivery_state;
  rd_kafka_conf_set_dr_msg_cb(configuration, delivery_report);
  rd_kafka_conf_set_opaque(configuration, &delivery_state);
  set_configuration(configuration, "bootstrap.servers", std::string(brokers).c_str());
  set_configuration(configuration, "enable.idempotence", "true");
  set_configuration(configuration, "acks", "all");
  set_configuration(configuration, "allow.auto.create.topics", "false");

  char error[512]{};
  rd_kafka_t *producer =
      rd_kafka_new(RD_KAFKA_PRODUCER, configuration, error, sizeof(error));
  if (producer == nullptr) {
    throw std::runtime_error(std::string("unable to create fixture producer: ") + error);
  }
  try {
    publish(producer, topic, open_barrier());
    publish(
        producer,
        topic,
        limit_order(
            "0198a001-0000-7000-8000-000000000002",
            "0198a001-0000-7000-8000-000000000012",
            "0198a001-0000-7000-8000-0000000000aa",
        simplematch::common::v2::SIDE_SELL));
    publish(
        producer,
        topic,
        limit_order(
            "0198a001-0000-7000-8000-000000000003",
            "0198a001-0000-7000-8000-000000000013",
            "0198a001-0000-7000-8000-0000000000bb",
        simplematch::common::v2::SIDE_BUY));
    const auto flush_error = rd_kafka_flush(producer, 10'000);
    if (flush_error != RD_KAFKA_RESP_ERR_NO_ERROR || delivery_state.failed) {
      const auto report_error = delivery_state.failed ? delivery_state.error : flush_error;
      throw std::runtime_error(
          std::string("fixture command delivery failed: ") + rd_kafka_err2str(report_error));
    }
  } catch (...) {
    rd_kafka_destroy(producer);
    throw;
  }
  rd_kafka_destroy(producer);
}

} // namespace

int main(int argc, char **argv) {
  if (argc != 3) {
    std::cerr << "usage: simplematch-matching-kafka-fixture-publisher BROKERS TOPIC\n";
    return 2;
  }
  try {
    publish_fixture(argv[1], argv[2]);
    return 0;
  } catch (const std::exception &failure) {
    std::cerr << failure.what() << '\n';
    return 1;
  }
}
