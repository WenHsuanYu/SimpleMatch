#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <fstream>
#include <iomanip>
#include <iostream>
#include <map>
#include <memory>
#include <set>
#include <sstream>
#include <stdexcept>
#include <string>
#include <string_view>
#include <vector>

#include <librdkafka/rdkafka.h>
#include <nlohmann/json.hpp>

#include "matching_runtime_v1.pb.h"

namespace {

using Json = nlohmann::json;
using Clock = std::chrono::steady_clock;
using Command = simplematch::matching::runtime::v1::MatchingCommand;

constexpr std::size_t kPairs = 4;
constexpr std::chrono::milliseconds kPollTimeout{250};
constexpr std::chrono::seconds kMeasurementTimeout{30};

struct Configuration {
  std::string brokers;
  std::string commands_topic;
  std::string events_topic;
  std::string trading_day;
  std::string trading_session;
  std::string artifact_sha256;
  std::string routing_version;
  std::string image_digest;
  std::int32_t partition{};
  std::string venue_mic;
  std::string symbol;
  std::string run_token;
  std::string report_path;
};

struct DeliveryState {
  bool failed{};
  rd_kafka_resp_err_t error{RD_KAFKA_RESP_ERR_NO_ERROR};
};

struct PublishedCommand {
  std::string command_id;
  Clock::time_point submitted_at;
};

struct KafkaDeleter {
  void operator()(rd_kafka_t *client) const {
    if (client != nullptr) {
      rd_kafka_destroy(client);
    }
  }
};

std::string kafka_error(rd_kafka_resp_err_t error) {
  return std::string(rd_kafka_err2name(error)) + ": " + rd_kafka_err2str(error);
}

void set_configuration(rd_kafka_conf_t *configuration, const char *name, std::string_view value) {
  char error[512]{};
  const std::string owned(value);
  if (rd_kafka_conf_set(configuration, name, owned.c_str(), error, sizeof(error)) !=
      RD_KAFKA_CONF_OK) {
    throw std::invalid_argument(std::string("invalid Kafka configuration ") + name + ": " + error);
  }
}

void delivery_report(rd_kafka_t *, const rd_kafka_message_t *message, void *opaque) {
  auto *state = static_cast<DeliveryState *>(opaque);
  if (message->err != RD_KAFKA_RESP_ERR_NO_ERROR) {
    state->failed = true;
    state->error = message->err;
  }
}

std::string hex_suffix(std::size_t index) {
  std::ostringstream encoded;
  encoded << std::hex << std::setw(12) << std::setfill('0') << index;
  const auto value = encoded.str();
  if (value.size() != 12) {
    throw std::invalid_argument("E2E command index exceeds the UUID suffix capacity");
  }
  return value;
}

std::string uuid_for(const Configuration &configuration, std::size_t index) {
  return "0198a00" + configuration.run_token.substr(7, 1) + "-" +
         configuration.run_token.substr(0, 4) + "-7000-8" +
         configuration.run_token.substr(4, 3) + "-" + hex_suffix(index);
}

Command base_command(const Configuration &configuration, std::string command_id) {
  Command command;
  auto *header = command.mutable_header();
  header->set_schema_version(1);
  header->set_command_id(std::move(command_id));
  header->set_trading_session_id(configuration.trading_session);
  header->set_partition_id(configuration.partition);
  header->mutable_artifact_identity()->set_trading_day(configuration.trading_day);
  header->mutable_artifact_identity()->set_content_sha256(configuration.artifact_sha256);
  header->set_routing_algorithm_version(configuration.routing_version);
  return command;
}

Command limit_order(
    const Configuration &configuration,
    std::size_t command_index,
    std::size_t order_index,
    std::int64_t price_units,
    simplematch::common::v2::Side side) {
  const std::string command_id = uuid_for(configuration, command_index);
  const std::string order_id = uuid_for(configuration, order_index);
  const std::string account_id = uuid_for(configuration, 0x100000 + command_index);
  auto command = base_command(configuration, command_id);
  auto *order = command.mutable_new_order();
  order->set_order_id(order_id);
  order->set_account_id(account_id);
  order->mutable_instrument()->set_venue_mic(configuration.venue_mic);
  order->mutable_instrument()->set_symbol(configuration.symbol);
  order->set_side(side);
  order->set_quantity_shares(100);
  order->set_limit_price_units(price_units);
  order->set_order_type(simplematch::common::v2::ORDER_TYPE_LIMIT);
  order->set_time_in_force(simplematch::common::v2::TIME_IN_FORCE_ROD);
  return command;
}

PublishedCommand publish(
    rd_kafka_t *producer,
    std::string_view topic,
    std::int32_t partition,
    const Command &command) {
  const std::string key = command.header().command_id();
  const std::string value = command.SerializeAsString();
  const auto submitted_at = Clock::now();
  const auto error = rd_kafka_producev(
      producer,
      RD_KAFKA_V_TOPIC(topic.data()),
      RD_KAFKA_V_PARTITION(partition),
      RD_KAFKA_V_KEY(key.data(), key.size()),
      RD_KAFKA_V_VALUE(const_cast<char *>(value.data()), value.size()),
      RD_KAFKA_V_MSGFLAGS(RD_KAFKA_MSG_F_COPY),
      RD_KAFKA_V_END);
  if (error != RD_KAFKA_RESP_ERR_NO_ERROR) {
    throw std::runtime_error("E2E command publication failed: " + kafka_error(error));
  }
  return {key, submitted_at};
}

std::unique_ptr<rd_kafka_t, KafkaDeleter> create_consumer(const Configuration &configuration) {
  rd_kafka_conf_t *raw = rd_kafka_conf_new();
  set_configuration(raw, "bootstrap.servers", configuration.brokers);
  set_configuration(raw, "group.id", "simplematch-e2e-" + configuration.run_token);
  set_configuration(raw, "enable.auto.commit", "false");
  set_configuration(raw, "auto.offset.reset", "latest");
  set_configuration(raw, "allow.auto.create.topics", "false");
  char error[512]{};
  rd_kafka_t *consumer = rd_kafka_new(RD_KAFKA_CONSUMER, raw, error, sizeof(error));
  if (consumer == nullptr) {
    rd_kafka_conf_destroy(raw);
    throw std::runtime_error(std::string("unable to create E2E consumer: ") + error);
  }
  const auto poll_error = rd_kafka_poll_set_consumer(consumer);
  if (poll_error != RD_KAFKA_RESP_ERR_NO_ERROR) {
    rd_kafka_destroy(consumer);
    throw std::runtime_error("unable to initialize E2E consumer: " + kafka_error(poll_error));
  }
  return std::unique_ptr<rd_kafka_t, KafkaDeleter>(consumer);
}

std::unique_ptr<rd_kafka_t, KafkaDeleter> create_producer(
    const Configuration &configuration,
    DeliveryState &delivery_state) {
  rd_kafka_conf_t *raw = rd_kafka_conf_new();
  rd_kafka_conf_set_dr_msg_cb(raw, delivery_report);
  rd_kafka_conf_set_opaque(raw, &delivery_state);
  set_configuration(raw, "bootstrap.servers", configuration.brokers);
  set_configuration(raw, "enable.idempotence", "true");
  set_configuration(raw, "acks", "all");
  set_configuration(raw, "allow.auto.create.topics", "false");
  char error[512]{};
  rd_kafka_t *producer = rd_kafka_new(RD_KAFKA_PRODUCER, raw, error, sizeof(error));
  if (producer == nullptr) {
    rd_kafka_conf_destroy(raw);
    throw std::runtime_error(std::string("unable to create E2E producer: ") + error);
  }
  return std::unique_ptr<rd_kafka_t, KafkaDeleter>(producer);
}

std::int64_t event_end_offset(
    rd_kafka_t *consumer, std::string_view topic, std::int32_t partition) {
  std::int64_t low = -1;
  std::int64_t high = -1;
  const std::string topic_name(topic);
  const auto error = rd_kafka_query_watermark_offsets(
      consumer, topic_name.c_str(), partition, &low, &high, 5000);
  if (error != RD_KAFKA_RESP_ERR_NO_ERROR) {
    throw std::runtime_error("unable to query E2E event watermark: " + kafka_error(error));
  }
  return high;
}

void assign_from_offset(
    rd_kafka_t *consumer, std::string_view topic, std::int32_t partition, std::int64_t offset) {
  rd_kafka_topic_partition_list_t *partitions = rd_kafka_topic_partition_list_new(1);
  const std::string topic_name(topic);
  auto *entry = rd_kafka_topic_partition_list_add(
      partitions, topic_name.c_str(), partition);
  entry->offset = offset;
  const auto error = rd_kafka_assign(consumer, partitions);
  rd_kafka_topic_partition_list_destroy(partitions);
  if (error != RD_KAFKA_RESP_ERR_NO_ERROR) {
    throw std::runtime_error("unable to assign E2E event consumer: " + kafka_error(error));
  }
}

std::int64_t percentile(std::vector<std::int64_t> values, double fraction) {
  if (values.empty()) {
    return 0;
  }
  std::sort(values.begin(), values.end());
  const auto index = static_cast<std::size_t>(
      std::min<double>(values.size() - 1, std::ceil(fraction * values.size()) - 1));
  return values[index];
}

Json measure(const Configuration &configuration) {
  auto consumer = create_consumer(configuration);
  const auto first_event_offset =
      event_end_offset(consumer.get(), configuration.events_topic, configuration.partition);
  const auto first_command_offset =
      event_end_offset(consumer.get(), configuration.commands_topic, configuration.partition);
  assign_from_offset(
      consumer.get(), configuration.events_topic, configuration.partition, first_event_offset);

  DeliveryState delivery_state;
  auto producer = create_producer(configuration, delivery_state);
  const auto started = Clock::now();
  std::map<std::string, Clock::time_point> submitted_commands;
  for (std::size_t pair = 0; pair < kPairs; ++pair) {
    const auto price_units = 1'000'000 + static_cast<std::int64_t>(pair);
    const auto sell = publish(
        producer.get(),
        configuration.commands_topic,
        configuration.partition,
        limit_order(
            configuration,
            pair * 2 + 1,
            0x200000 + pair * 2,
            price_units,
            simplematch::common::v2::SIDE_SELL));
    const auto buy = publish(
        producer.get(),
        configuration.commands_topic,
        configuration.partition,
        limit_order(
            configuration,
            pair * 2 + 2,
            0x200000 + pair * 2 + 1,
            price_units,
            simplematch::common::v2::SIDE_BUY));
    submitted_commands.emplace(sell.command_id, sell.submitted_at);
    submitted_commands.emplace(buy.command_id, buy.submitted_at);
  }
  const auto flush_error = rd_kafka_flush(producer.get(), 10'000);
  if (flush_error != RD_KAFKA_RESP_ERR_NO_ERROR || delivery_state.failed) {
    const auto error = delivery_state.failed ? delivery_state.error : flush_error;
    throw std::runtime_error("E2E command delivery failed: " + kafka_error(error));
  }

  const auto final_command_offset =
      event_end_offset(consumer.get(), configuration.commands_topic, configuration.partition);

  const std::size_t expected_events = kPairs * 2;
  std::size_t observed_events = 0;
  std::size_t duplicate_events = 0;
  std::set<std::string> event_keys;
  std::vector<std::int64_t> latencies;
  const auto deadline = started + kMeasurementTimeout;
  while (Clock::now() < deadline && observed_events < expected_events) {
    rd_kafka_message_t *message = rd_kafka_consumer_poll(consumer.get(), kPollTimeout.count());
    if (message == nullptr) {
      continue;
    }
    if (message->err == RD_KAFKA_RESP_ERR__TIMED_OUT ||
        message->err == RD_KAFKA_RESP_ERR__PARTITION_EOF) {
      rd_kafka_message_destroy(message);
      continue;
    }
    if (message->err != RD_KAFKA_RESP_ERR_NO_ERROR) {
      const auto error = message->err;
      rd_kafka_message_destroy(message);
      throw std::runtime_error("E2E event consumption failed: " + kafka_error(error));
    }
    simplematch::matching::runtime::v1::MatchingEvent event;
    if (!event.ParseFromArray(message->payload, static_cast<int>(message->len))) {
      rd_kafka_message_destroy(message);
      throw std::runtime_error("E2E event payload was not a MatchingEvent");
    }
    const auto submitted = submitted_commands.find(event.source_command_id());
    if (submitted == submitted_commands.end()) {
      rd_kafka_message_destroy(message);
      continue;
    }
    if (event.partition_id() != configuration.partition ||
        event.trading_session_id() != configuration.trading_session ||
        event.artifact_identity().trading_day() != configuration.trading_day ||
        event.artifact_identity().content_sha256() != configuration.artifact_sha256) {
      rd_kafka_message_destroy(message);
      throw std::runtime_error("E2E event identity did not match the submitted command");
    }
    ++observed_events;
    if (!event_keys.insert(event.event_id()).second) {
      ++duplicate_events;
    }
    latencies.push_back(
        std::chrono::duration_cast<std::chrono::nanoseconds>(Clock::now() - submitted->second).count());
    rd_kafka_message_destroy(message);
  }

  const auto loss = expected_events > observed_events ? expected_events - observed_events : 0;
  const auto elapsed_ns = std::max<std::int64_t>(
      1, std::chrono::duration_cast<std::chrono::nanoseconds>(Clock::now() - started).count());
  const auto latency_max = latencies.empty() ? 0 : *std::max_element(latencies.begin(), latencies.end());
  return {
      {"schema_version", 1},
      {"status", loss == 0 && duplicate_events == 0 ? "PASSED" : "FAILED"},
      {"expected_events", expected_events},
      {"observed_events", observed_events},
      {"kafka_e2e_latency_ns", {{"p50", percentile(latencies, 0.50)},
                                 {"p99", percentile(latencies, 0.99)},
                                 {"p99_9", percentile(latencies, 0.999)},
                                 {"max", latency_max}}},
      {"commands_per_second", static_cast<double>(kPairs * 2) * 1'000'000'000.0 / elapsed_ns},
      {"events_per_second", static_cast<double>(observed_events) * 1'000'000'000.0 / elapsed_ns},
      {"loss", loss},
      {"duplicates", duplicate_events},
      {"command_start_offset", first_command_offset},
      {"command_end_offset", final_command_offset},
      {"partition", configuration.partition},
      {"instrument", { {"venue_mic", configuration.venue_mic},
                        {"symbol", configuration.symbol} }},
      {"command_range_size",
       final_command_offset >= first_command_offset
           ? final_command_offset - first_command_offset
           : 0},
      {"first_event_offset", first_event_offset},
      {"run_token", configuration.run_token},
      {"kafka_e2e_latency_definition",
       "per-event monotonic elapsed time from local rd_kafka_producev submission to receipt of the "
       "MatchingEvent with the same source_command_id"},
      {"claim_boundary", {"local Kafka E2E smoke", "not a production latency claim"}}};
}

Configuration parse_configuration(int argc, char **argv) {
  if (argc != 14) {
    throw std::invalid_argument(
        "usage: simplematch-matching-e2e-certification BROKERS COMMANDS_TOPIC EVENTS_TOPIC "
        "TRADING_DAY TRADING_SESSION ARTIFACT_SHA256 ROUTING_VERSION IMAGE_DIGEST PARTITION "
        "VENUE_MIC SYMBOL RUN_TOKEN REPORT");
  }
  Configuration configuration;
  configuration.brokers = argv[1];
  configuration.commands_topic = argv[2];
  configuration.events_topic = argv[3];
  configuration.trading_day = argv[4];
  configuration.trading_session = argv[5];
  configuration.artifact_sha256 = argv[6];
  configuration.routing_version = argv[7];
  configuration.image_digest = argv[8];
  const std::string partition_text = argv[9];
  std::size_t parsed_characters = 0;
  const auto parsed_partition = std::stol(partition_text, &parsed_characters);
  if (parsed_characters != partition_text.size() || parsed_partition < 0 || parsed_partition >= 15) {
    throw std::invalid_argument("PARTITION must be between 0 and 14");
  }
  configuration.partition = static_cast<std::int32_t>(parsed_partition);
  configuration.venue_mic = argv[10];
  configuration.symbol = argv[11];
  configuration.run_token = argv[12];
  configuration.report_path = argv[13];
  if (configuration.venue_mic.empty() || configuration.symbol.empty()) {
    throw std::invalid_argument("VENUE_MIC and SYMBOL must not be empty");
  }
  if (configuration.run_token.size() != 8 ||
      configuration.run_token.find_first_not_of("0123456789abcdef") != std::string::npos) {
    throw std::invalid_argument("RUN_TOKEN must be eight lowercase hexadecimal characters");
  }
  return configuration;
}

} // namespace

int main(int argc, char **argv) {
  try {
    const auto configuration = parse_configuration(argc, argv);
    const auto report = measure(configuration);
    std::ofstream output(configuration.report_path, std::ios::trunc);
    if (!output) {
      throw std::runtime_error("unable to write E2E report: " + configuration.report_path);
    }
    output << report.dump(2) << '\n';
    output.flush();
    if (!output) {
      throw std::runtime_error("unable to flush E2E report: " + configuration.report_path);
    }
    std::cout << report.dump(2) << '\n';
    return report.at("status") == "PASSED" ? 0 : 1;
  } catch (const std::exception &failure) {
    std::cerr << failure.what() << '\n';
    return 2;
  }
}
