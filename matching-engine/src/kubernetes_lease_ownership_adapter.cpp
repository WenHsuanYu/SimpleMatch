#include "simplematch/matching/runtime/kubernetes_lease_ownership_adapter.hpp"

#include <chrono>
#include <cstddef>
#include <ctime>
#include <iomanip>
#include <limits>
#include <optional>
#include <sstream>
#include <stdexcept>
#include <string_view>
#include <utility>

#include <curl/curl.h>
#include <nlohmann/json.hpp>

namespace simplematch::matching {
namespace {

using Json = nlohmann::json;
using SystemTime = std::chrono::system_clock::time_point;

struct CurlGlobalLifecycle {
  CurlGlobalLifecycle() {
    if (curl_global_init(CURL_GLOBAL_DEFAULT) != CURLE_OK) {
      throw std::runtime_error("unable to initialize libcurl");
    }
  }

  ~CurlGlobalLifecycle() { curl_global_cleanup(); }
};

const CurlGlobalLifecycle kCurlGlobalLifecycle;

std::size_t append_response(char *data, std::size_t size, std::size_t count, void *context) {
  if (count != 0 && size > std::numeric_limits<std::size_t>::max() / count) {
    return 0;
  }
  const std::size_t bytes = size * count;
  static_cast<std::string *>(context)->append(data, bytes);
  return bytes;
}

std::optional<SystemTime> parse_timestamp(std::string_view value) {
  if (value.size() < 20 || value.back() != 'Z') {
    return std::nullopt;
  }
  std::tm calendar{};
  std::istringstream input(std::string(value.substr(0, 19)));
  input >> std::get_time(&calendar, "%Y-%m-%dT%H:%M:%S");
  if (input.fail()) {
    return std::nullopt;
  }
  const std::time_t seconds = timegm(&calendar);
  if (seconds == static_cast<std::time_t>(-1)) {
    return std::nullopt;
  }
  std::chrono::milliseconds fraction{};
  if (value.size() > 20 && value[19] == '.') {
    std::size_t digits = 0;
    int milliseconds = 0;
    for (std::size_t index = 20; index < value.size() - 1 && digits < 3; ++index) {
      const char digit = value[index];
      if (digit < '0' || digit > '9') {
        break;
      }
      milliseconds = milliseconds * 10 + (digit - '0');
      ++digits;
    }
    while (digits < 3) {
      milliseconds *= 10;
      ++digits;
    }
    fraction = std::chrono::milliseconds(milliseconds);
  }
  return std::chrono::system_clock::from_time_t(seconds) + fraction;
}

std::string format_timestamp(SystemTime value) {
  const auto seconds = std::chrono::time_point_cast<std::chrono::seconds>(value);
  const auto microseconds =
      std::chrono::duration_cast<std::chrono::microseconds>(value - seconds).count();
  const std::time_t calendar_time = std::chrono::system_clock::to_time_t(seconds);
  std::tm calendar{};
  if (gmtime_r(&calendar_time, &calendar) == nullptr) {
    throw std::runtime_error("unable to format Kubernetes Lease timestamp");
  }
  std::ostringstream output;
  output << std::put_time(&calendar, "%Y-%m-%dT%H:%M:%S") << '.' << std::setfill('0')
         << std::setw(6) << microseconds << 'Z';
  return output.str();
}

void append_header(curl_slist **headers, const std::string &value) {
  *headers = curl_slist_append(*headers, value.c_str());
  if (*headers == nullptr) {
    throw std::runtime_error("unable to allocate Kubernetes Lease HTTP headers");
  }
}

} // namespace

namespace detail {

std::string format_kubernetes_lease_timestamp(SystemTime value) {
  return format_timestamp(value);
}

} // namespace detail

struct KubernetesLeaseOwnershipAdapter::Implementation {
  CURL *curl{curl_easy_init()};

  ~Implementation() {
    if (curl != nullptr) {
      curl_easy_cleanup(curl);
    }
  }
};

KubernetesLeaseOwnershipAdapter::KubernetesLeaseOwnershipAdapter(
    LeaseFencedPartitionOwnershipPermit &permit,
    PartitionOwnershipIdentity expected_identity,
    std::string lease_name,
    std::string namespace_name,
    std::string api_server,
    std::string bearer_token,
    std::string ca_certificate_path,
    std::chrono::milliseconds request_timeout)
    : permit_(permit),
      expected_identity_(std::move(expected_identity)),
      lease_name_(std::move(lease_name)),
      namespace_name_(std::move(namespace_name)),
      api_server_(std::move(api_server)),
      bearer_token_(std::move(bearer_token)),
      ca_certificate_path_(std::move(ca_certificate_path)),
      request_timeout_(request_timeout),
      implementation_(std::make_unique<Implementation>()) {
  if (expected_identity_.partition_id < 0 || expected_identity_.holder_identity.empty() ||
      expected_identity_.trading_session_id.empty() || lease_name_.empty() ||
      namespace_name_.empty() || api_server_.empty() || bearer_token_.empty() ||
      request_timeout_ <= std::chrono::milliseconds::zero() ||
      implementation_->curl == nullptr) {
    throw std::invalid_argument("incomplete Kubernetes Lease adapter configuration");
  }
  if (!api_server_.starts_with("https://")) {
    throw std::invalid_argument("Kubernetes API server must use HTTPS");
  }
}

KubernetesLeaseOwnershipAdapter::~KubernetesLeaseOwnershipAdapter() = default;

bool KubernetesLeaseOwnershipAdapter::refresh() {
  const auto now = std::chrono::system_clock::now();
  const auto steady_now = std::chrono::steady_clock::now();
  auto mark_uncertain = [this, steady_now]() {
    permit_.report_renewal_uncertainty(steady_now);
    permit_.evaluate_at(steady_now);
    return false;
  };

  curl_easy_reset(implementation_->curl);
  const std::string url = api_server_ + "/apis/coordination.k8s.io/v1/namespaces/" +
                          namespace_name_ + "/leases/" + lease_name_;
  std::string response;
  curl_easy_setopt(implementation_->curl, CURLOPT_URL, url.c_str());
  curl_easy_setopt(implementation_->curl, CURLOPT_WRITEFUNCTION, append_response);
  curl_easy_setopt(implementation_->curl, CURLOPT_WRITEDATA, &response);
  curl_easy_setopt(implementation_->curl, CURLOPT_TIMEOUT_MS, request_timeout_.count());
  curl_easy_setopt(implementation_->curl, CURLOPT_SSL_VERIFYPEER, 1L);
  curl_easy_setopt(implementation_->curl, CURLOPT_SSL_VERIFYHOST, 2L);
  if (!ca_certificate_path_.empty()) {
    curl_easy_setopt(implementation_->curl, CURLOPT_CAINFO, ca_certificate_path_.c_str());
  }
  curl_slist *headers = nullptr;
  try {
    append_header(&headers, "Authorization: Bearer " + bearer_token_);
    append_header(&headers, "Accept: application/json");
  } catch (...) {
    curl_slist_free_all(headers);
    throw;
  }
  curl_easy_setopt(implementation_->curl, CURLOPT_HTTPHEADER, headers);
  const CURLcode get_result = curl_easy_perform(implementation_->curl);
  long http_status = 0;
  curl_easy_getinfo(implementation_->curl, CURLINFO_RESPONSE_CODE, &http_status);
  curl_slist_free_all(headers);
  if (get_result != CURLE_OK || http_status != 200) {
    return mark_uncertain();
  }

  Json lease;
  try {
    lease = Json::parse(response);
  } catch (const Json::exception &) {
    return mark_uncertain();
  }
  const Json &metadata = lease["metadata"];
  const Json &spec = lease["spec"];
  if (!metadata.is_object() || !spec.is_object() || !metadata["resourceVersion"].is_string()) {
    return mark_uncertain();
  }
  const std::string resource_version = metadata["resourceVersion"].get<std::string>();
  const std::string holder =
      spec.contains("holderIdentity") && spec["holderIdentity"].is_string()
          ? spec["holderIdentity"].get<std::string>()
          : std::string{};
  const int duration_seconds = spec.value("leaseDurationSeconds", 15);
  const auto renewed_at =
      spec.contains("renewTime") && spec["renewTime"].is_string()
          ? parse_timestamp(spec["renewTime"].get<std::string>())
          : std::optional<SystemTime>{};
  const bool expired = holder.empty() || !renewed_at.has_value() ||
                       now >= *renewed_at + std::chrono::seconds(duration_seconds);
  if (!holder.empty() && holder != expected_identity_.holder_identity && !expired) {
    return mark_uncertain();
  }

  Json patch = {{"metadata", Json::object()}};
  patch["metadata"]["resourceVersion"] = resource_version;
  patch["spec"] = {
      {"holderIdentity", expected_identity_.holder_identity},
      {"leaseDurationSeconds", duration_seconds},
      {"renewTime", format_timestamp(now)}};
  if (holder != expected_identity_.holder_identity) {
    patch["spec"]["acquireTime"] = format_timestamp(now);
    patch["spec"]["leaseTransitions"] = spec.value("leaseTransitions", 0) + 1;
  }

  response.clear();
  curl_easy_reset(implementation_->curl);
  curl_easy_setopt(implementation_->curl, CURLOPT_URL, url.c_str());
  curl_easy_setopt(implementation_->curl, CURLOPT_CUSTOMREQUEST, "PATCH");
  const std::string patch_body = patch.dump();
  curl_easy_setopt(implementation_->curl, CURLOPT_POSTFIELDS, patch_body.c_str());
  curl_easy_setopt(implementation_->curl, CURLOPT_POSTFIELDSIZE, patch_body.size());
  curl_easy_setopt(implementation_->curl, CURLOPT_WRITEFUNCTION, append_response);
  curl_easy_setopt(implementation_->curl, CURLOPT_WRITEDATA, &response);
  curl_easy_setopt(implementation_->curl, CURLOPT_TIMEOUT_MS, request_timeout_.count());
  curl_easy_setopt(implementation_->curl, CURLOPT_SSL_VERIFYPEER, 1L);
  curl_easy_setopt(implementation_->curl, CURLOPT_SSL_VERIFYHOST, 2L);
  if (!ca_certificate_path_.empty()) {
    curl_easy_setopt(implementation_->curl, CURLOPT_CAINFO, ca_certificate_path_.c_str());
  }
  headers = nullptr;
  try {
    append_header(&headers, "Authorization: Bearer " + bearer_token_);
    append_header(&headers, "Accept: application/json");
    append_header(&headers, "Content-Type: application/merge-patch+json");
  } catch (...) {
    curl_slist_free_all(headers);
    throw;
  }
  curl_easy_setopt(implementation_->curl, CURLOPT_HTTPHEADER, headers);
  const CURLcode patch_result = curl_easy_perform(implementation_->curl);
  http_status = 0;
  curl_easy_getinfo(implementation_->curl, CURLINFO_RESPONSE_CODE, &http_status);
  curl_slist_free_all(headers);
  if (patch_result != CURLE_OK || http_status < 200 || http_status >= 300) {
    return mark_uncertain();
  }

  return permit_.confirm_renewal(expected_identity_, steady_now);
}

} // namespace simplematch::matching
