#include "simplematch/matching/config/market_reference_artifact.hpp"
#include "simplematch/matching/core/deterministic_matching_core.hpp"
#include "simplematch/matching/runtime/kubernetes_lease_ownership_adapter.hpp"
#include "simplematch/matching/runtime/matching_partition_runtime_driver.hpp"
#include "simplematch/matching/runtime/rdkafka_runtime_adapter.hpp"

#include <charconv>
#include <chrono>
#include <cstdint>
#include <cstdlib>
#include <filesystem>
#include <fstream>
#include <iomanip>
#include <iostream>
#include <memory>
#include <optional>
#include <sstream>
#include <stdexcept>
#include <string>
#include <string_view>
#include <thread>
#include <utility>
#include <vector>

#include <nlohmann/json.hpp>

namespace simplematch::matching {
namespace {

using Json = nlohmann::json;
using namespace std::chrono_literals;

constexpr std::int32_t kPartitionCount = 15;
constexpr std::string_view kDefaultStatusPath = "/var/lib/simplematch/matching/runtime-status";

struct RuntimeConfiguration {
  std::string brokers;
  std::string consumer_group;
  std::string commands_topic;
  std::string events_topic;
  std::string artifact_path;
  std::string checksum_path;
  std::string trading_day;
  std::string trading_session_id;
  std::string matching_image_digest;
  std::string baseline_path;
  std::string status_path;
  std::int32_t partition_id{};
  std::size_t input_capacity{};
  std::size_t output_capacity{};
  std::size_t maximum_resting_orders{};
  std::size_t maximum_distinct_commands{};
  std::size_t maximum_pending_publications{};
  std::chrono::milliseconds poll_timeout{};
  std::chrono::milliseconds flush_timeout{};
  std::chrono::milliseconds lease_request_timeout{};
  std::chrono::seconds lease_uncertainty_deadline{};
  std::string lease_mode;
  bool static_ownership_confirmed{};
};

std::string environment_value(std::string_view name, std::string fallback = {}) {
  const std::string key(name);
  const char *value = std::getenv(key.c_str());
  return value == nullptr ? std::move(fallback) : std::string(value);
}

std::string required_environment(std::string_view name) {
  const auto value = environment_value(name);
  if (value.empty()) {
    throw std::invalid_argument("required Matching environment variable is missing: " +
                                std::string(name));
  }
  return value;
}

template <typename Integer>
Integer positive_environment(std::string_view name, Integer fallback) {
  const auto text = environment_value(name);
  if (text.empty()) {
    return fallback;
  }
  Integer value{};
  const auto *begin = text.data();
  const auto *end = begin + text.size();
  const auto parsed = std::from_chars(begin, end, value);
  if (parsed.ec != std::errc{} || parsed.ptr != end || value <= 0) {
    throw std::invalid_argument("Matching environment variable must be positive: " +
                                std::string(name));
  }
  return value;
}

template <typename Integer>
Integer non_negative_environment(std::string_view name, Integer fallback) {
  const auto text = environment_value(name);
  if (text.empty()) {
    return fallback;
  }
  Integer value{};
  const auto *begin = text.data();
  const auto *end = begin + text.size();
  const auto parsed = std::from_chars(begin, end, value);
  if (parsed.ec != std::errc{} || parsed.ptr != end || value < 0) {
    throw std::invalid_argument("Matching environment variable must not be negative: " +
                                std::string(name));
  }
  return value;
}

bool boolean_environment(std::string_view name, bool fallback) {
  const auto value = environment_value(name);
  if (value.empty()) {
    return fallback;
  }
  if (value == "true") {
    return true;
  }
  if (value == "false") {
    return false;
  }
  throw std::invalid_argument("Matching environment variable must be true or false: " +
                              std::string(name));
}

std::string read_file(const std::string &path) {
  std::ifstream input(path, std::ios::binary);
  if (!input) {
    throw std::runtime_error("unable to read Matching file: " + path);
  }
  std::ostringstream content;
  content << input.rdbuf();
  if (!input.good() && !input.eof()) {
    throw std::runtime_error("unable to read complete Matching file: " + path);
  }
  return content.str();
}

std::string trim_text(std::string value) {
  while (!value.empty() && (value.back() == '\n' || value.back() == '\r' || value.back() == ' ' ||
                            value.back() == '\t')) {
    value.pop_back();
  }
  std::size_t first = 0;
  while (first < value.size() && (value[first] == ' ' || value[first] == '\t')) {
    ++first;
  }
  return value.substr(first);
}

RuntimeConfiguration load_configuration() {
  RuntimeConfiguration configuration{
      required_environment("MATCHING_KAFKA_BROKERS"),
      environment_value("MATCHING_CONSUMER_GROUP", "matching-partition-consumer"),
      environment_value("MATCHING_COMMANDS_TOPIC", "matching.commands"),
      environment_value("MATCHING_EVENTS_TOPIC", "matching.events"),
      required_environment("MATCHING_ARTIFACT_PATH"),
      environment_value("MATCHING_ARTIFACT_CHECKSUM_PATH", "/etc/simplematch/market-reference/market_reference.sha256"),
      required_environment("MATCHING_TRADING_DAY"),
      required_environment("MATCHING_TRADING_SESSION_ID"),
      required_environment("MATCHING_MATCHING_IMAGE_DIGEST"),
      environment_value("MATCHING_BASELINE_PATH", "/var/lib/simplematch/matching/partition-baseline.json"),
      environment_value("MATCHING_STATUS_PATH", std::string(kDefaultStatusPath)),
      non_negative_environment<std::int32_t>("MATCHING_PARTITION_ID", -1),
      positive_environment<std::size_t>("MATCHING_INPUT_CAPACITY", 1024),
      positive_environment<std::size_t>("MATCHING_OUTPUT_CAPACITY", 1048576),
      positive_environment<std::size_t>("MATCHING_MAX_RESTING_ORDERS_PER_INSTRUMENT", 4096),
      positive_environment<std::size_t>("MATCHING_MAX_DISTINCT_COMMANDS", 100000),
      positive_environment<std::size_t>("MATCHING_MAX_PENDING_PUBLICATIONS", 1000000),
      std::chrono::milliseconds(
          positive_environment<std::int64_t>("MATCHING_POLL_TIMEOUT_MILLIS", 100)),
      std::chrono::milliseconds(
          positive_environment<std::int64_t>("MATCHING_FLUSH_TIMEOUT_MILLIS", 5000)),
      std::chrono::milliseconds(
          positive_environment<std::int64_t>("MATCHING_LEASE_REQUEST_TIMEOUT_MILLIS", 1000)),
      std::chrono::seconds(
          positive_environment<std::int64_t>("MATCHING_LEASE_UNCERTAINTY_FENCE_SECONDS", 5)),
      environment_value("MATCHING_LEASE_MODE", "kubernetes"),
      boolean_environment("MATCHING_STATIC_OWNERSHIP_CONFIRMED", false)};
  if (configuration.partition_id < 0 || configuration.partition_id >= kPartitionCount) {
    throw std::invalid_argument("MATCHING_PARTITION_ID must be between 0 and 14");
  }
  return configuration;
}

void write_status(const std::string &path, std::string_view status) {
  const std::filesystem::path destination(path);
  if (!destination.parent_path().empty()) {
    std::filesystem::create_directories(destination.parent_path());
  }
  const std::filesystem::path temporary = destination.string() + ".next";
  std::ofstream output(temporary, std::ios::trunc);
  if (!output) {
    throw std::runtime_error("unable to write Matching runtime status");
  }
  output << status << '\n';
  output.flush();
  if (!output) {
    throw std::runtime_error("unable to flush Matching runtime status");
  }
  std::filesystem::rename(temporary, destination);
}

bool status_is(const std::string &path, std::string_view expected) {
  try {
    std::ifstream input(path);
    std::string actual;
    std::getline(input, actual);
    return actual == expected;
  } catch (const std::ios_base::failure &) {
    return false;
  }
}

int run_probe(std::string_view mode) {
  const auto path = environment_value("MATCHING_STATUS_PATH", std::string(kDefaultStatusPath));
  if (mode == "readiness") {
    return status_is(path, "READY") ? 0 : 1;
  }
  if (mode == "liveness") {
    if (!status_is(path, "RUNNING") && !status_is(path, "READY")) {
      return 1;
    }
    try {
      return std::chrono::duration_cast<std::chrono::seconds>(
                     std::filesystem::file_time_type::clock::now() -
                     std::filesystem::last_write_time(path)) <= 30s
                 ? 0
                 : 1;
    } catch (const std::filesystem::filesystem_error &) {
      return 1;
    }
  }
  throw std::invalid_argument("Matching command must be readiness or liveness");
}

std::vector<CoreInstrument> assigned_instruments(
    const std::string &artifact_json, std::int32_t partition_id) {
  const Json artifact = Json::parse(artifact_json);
  const Json &assignments = artifact.at("routingPolicy").at("assignments");
  std::vector<CoreInstrument> instruments;
  for (const auto &assignment : assignments) {
    if (assignment.at("partitionId").get<std::int32_t>() != partition_id) {
      continue;
    }
    const auto instrument = CoreInstrument::create(
        assignment.at("venueMic").get<std::string>(), assignment.at("symbol").get<std::string>());
    if (!instrument.has_value()) {
      throw std::runtime_error("artifact contains an invalid Matching instrument");
    }
    instruments.push_back(*instrument);
  }
  if (instruments.empty()) {
    throw std::runtime_error("Matching partition has no assigned instruments");
  }
  return instruments;
}

std::string artifact_routing_version(const std::string &artifact_json) {
  return Json::parse(artifact_json).at("metadata").at("routingAlgorithmVersion").get<std::string>();
}

std::unique_ptr<KubernetesLeaseOwnershipAdapter> create_lease_adapter(
    const RuntimeConfiguration &configuration,
    LeaseFencedPartitionOwnershipPermit &permit,
    const PartitionOwnershipIdentity &identity) {
  if (configuration.lease_mode == "static") {
    if (!configuration.static_ownership_confirmed) {
      throw std::invalid_argument(
          "static Matching Lease mode requires MATCHING_STATIC_OWNERSHIP_CONFIRMED=true");
    }
    if (!permit.confirm_renewal(identity, std::chrono::steady_clock::now())) {
      throw std::runtime_error("static Matching ownership confirmation failed");
    }
    return nullptr;
  }
  if (configuration.lease_mode != "kubernetes") {
    throw std::invalid_argument("MATCHING_LEASE_MODE must be kubernetes or static");
  }
  const auto host = required_environment("KUBERNETES_SERVICE_HOST");
  const auto port = environment_value("KUBERNETES_SERVICE_PORT_HTTPS", "443");
  const auto namespace_path =
      environment_value("MATCHING_NAMESPACE_PATH", "/var/run/secrets/kubernetes.io/serviceaccount/namespace");
  const auto token_path =
      environment_value("MATCHING_TOKEN_PATH", "/var/run/secrets/kubernetes.io/serviceaccount/token");
  const auto ca_path =
      environment_value("MATCHING_CA_CERTIFICATE_PATH", "/var/run/secrets/kubernetes.io/serviceaccount/ca.crt");
  const auto namespace_name = trim_text(read_file(namespace_path));
  const auto token = trim_text(read_file(token_path));
  const auto lease_prefix = environment_value("MATCHING_LEASE_NAME_PREFIX", "matching-partition-");
  std::ostringstream lease_name;
  lease_name << lease_prefix << std::setfill('0') << std::setw(2) << configuration.partition_id;
  return std::make_unique<KubernetesLeaseOwnershipAdapter>(
      permit,
      identity,
      lease_name.str(),
      namespace_name,
      "https://" + host + ":" + port,
      token,
      ca_path,
      configuration.lease_request_timeout);
}

int run_runtime() {
  RuntimeConfiguration configuration = load_configuration();
  write_status(configuration.status_path, "STARTING");
  try {
    const std::string artifact_json = read_file(configuration.artifact_path);
    const std::string checksum = read_file(configuration.checksum_path);
    const auto decision = MarketReferenceArtifactLoader().load(
        artifact_json, checksum, configuration.trading_day);
    if (decision.action != MarketReferenceArtifactAction::kProceed) {
      write_status(configuration.status_path, "FAILED");
      throw std::runtime_error("Matching artifact rejected: " + decision.reason);
    }

    const std::string artifact_identity =
        configuration.trading_day + ":" + trim_text(checksum);
    const PartitionOwnershipIdentity ownership_identity{
        configuration.partition_id,
        required_environment("MATCHING_POD_NAME") + ":" + required_environment("MATCHING_POD_UID"),
        configuration.trading_session_id};
    auto permit = std::make_shared<LeaseFencedPartitionOwnershipPermit>(
        ownership_identity, configuration.lease_uncertainty_deadline);
    auto lease_adapter = create_lease_adapter(configuration, *permit, ownership_identity);
    if (lease_adapter != nullptr && !lease_adapter->refresh()) {
      write_status(configuration.status_path, "NOT_READY");
      return 3;
    }

    const PinnedMatchingIdentity pinned_identity{
        artifact_identity,
        configuration.trading_session_id,
        artifact_routing_version(artifact_json),
        configuration.matching_image_digest,
        1,
        1,
        1};
    auto core = std::make_unique<DeterministicMatchingCore>(
        assigned_instruments(artifact_json, configuration.partition_id),
        configuration.maximum_resting_orders,
        configuration.partition_id);
    const DirectKafkaPartitionAssignment assignment{
        configuration.commands_topic, configuration.partition_id};
    RdkafkaDirectPartitionKafkaConsumer consumer(
        configuration.brokers,
        configuration.consumer_group + "-" + std::to_string(configuration.partition_id),
        configuration.poll_timeout);
    RdkafkaMatchingEventPublisher publisher(
        configuration.brokers, configuration.events_topic, configuration.flush_timeout);
    PartitionReplayCoordinator coordinator(
        assignment,
        pinned_identity,
        permit,
        std::move(core),
        configuration.input_capacity,
        configuration.output_capacity,
        configuration.maximum_distinct_commands,
        configuration.maximum_pending_publications);
    PvcBaselineMetadataStore baseline_store(configuration.baseline_path);
    MatchingPartitionRuntimeDriver driver(consumer, coordinator, publisher);
    if (!driver.start(&baseline_store)) {
      write_status(configuration.status_path, "NOT_READY");
      return 3;
    }
    std::optional<PartitionBaselineMetadata> last_saved_baseline;
    auto next_lease_refresh = std::chrono::steady_clock::now();
    write_status(configuration.status_path, "RUNNING");
    for (;;) {
      const auto now = std::chrono::steady_clock::now();
      if (lease_adapter != nullptr && now >= next_lease_refresh) {
        if (!lease_adapter->refresh() && !permit->allows_processing()) {
          write_status(configuration.status_path, "NOT_READY");
          return 3;
        }
        next_lease_refresh = now + 2s;
      }
      const auto step = driver.run_once();
      if (step == MatchingPartitionDriverStep::kFailedClosed ||
          step == MatchingPartitionDriverStep::kOwnershipDenied) {
        write_status(configuration.status_path, "FAILED");
        return 4;
      }
      if (const auto baseline = coordinator.baseline_metadata();
          baseline.has_value() && baseline != last_saved_baseline) {
        baseline_store.save(*baseline);
        last_saved_baseline = baseline;
      }
      if (coordinator.status().state == PartitionSessionState::kOpen &&
          permit->allows_processing()) {
        write_status(configuration.status_path, "READY");
      }
    }
  } catch (const std::exception &failure) {
    try {
      write_status(configuration.status_path, "FAILED");
    } catch (const std::exception &) {
    }
    std::cerr << failure.what() << '\n';
    return 2;
  }
}

} // namespace
} // namespace simplematch::matching

int main(int argc, char **argv) {
  try {
    if (argc == 2 && (std::string_view(argv[1]) == "readiness" ||
                      std::string_view(argv[1]) == "liveness")) {
      return simplematch::matching::run_probe(argv[1]);
    }
    if (argc == 1) {
      return simplematch::matching::run_runtime();
    }
    throw std::invalid_argument("usage: simplematch-matching [readiness|liveness]");
  } catch (const std::exception &failure) {
    std::cerr << failure.what() << '\n';
    return 2;
  }
}
