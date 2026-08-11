#pragma once

#include "simplematch/matching/runtime/partition_ownership_permit.hpp"

#include <chrono>
#include <memory>
#include <string>

namespace simplematch::matching {

namespace detail {

/** Formats a Kubernetes Lease timestamp using RFC 3339 microsecond precision. */
std::string format_kubernetes_lease_timestamp(std::chrono::system_clock::time_point value);

} // namespace detail

/**
 * Renews one pre-created Kubernetes Lease and translates the result into a domain ownership
 * permit. Network calls are made by the runtime adapter, never by the matching core.
 */
class KubernetesLeaseOwnershipAdapter final {
public:
  KubernetesLeaseOwnershipAdapter(
      LeaseFencedPartitionOwnershipPermit &permit,
      PartitionOwnershipIdentity expected_identity,
      std::string lease_name,
      std::string namespace_name,
      std::string api_server,
      std::string bearer_token,
      std::string ca_certificate_path,
      std::chrono::milliseconds request_timeout);
  ~KubernetesLeaseOwnershipAdapter();

  KubernetesLeaseOwnershipAdapter(const KubernetesLeaseOwnershipAdapter &) = delete;
  KubernetesLeaseOwnershipAdapter &operator=(const KubernetesLeaseOwnershipAdapter &) = delete;

  /** Attempts one GET/renew cycle and returns true only after the Lease is confirmed. */
  [[nodiscard]] bool refresh();

private:
  struct Implementation;

  LeaseFencedPartitionOwnershipPermit &permit_;
  PartitionOwnershipIdentity expected_identity_;
  std::string lease_name_;
  std::string namespace_name_;
  std::string api_server_;
  std::string bearer_token_;
  std::string ca_certificate_path_;
  std::chrono::milliseconds request_timeout_;
  std::unique_ptr<Implementation> implementation_;
};

} // namespace simplematch::matching
