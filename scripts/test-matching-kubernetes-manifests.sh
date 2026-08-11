#!/usr/bin/env bash

set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd -- "$script_dir/.." && pwd)"
manifest_dir="$repo_root/deploy/k8s"

ruby -rjson -ryaml - "$manifest_dir" <<'RUBY'
manifest_dir = ARGV.fetch(0)

def load_documents(path)
  visitor = Psych::Visitors::ToRuby.create
  Psych.parse_stream(File.read(path, encoding: "UTF-8")).children.map { |document| visitor.accept(document) }.compact
end

def require_value(condition, message)
  return if condition

  warn message
  exit 1
end

statefulset = load_documents(File.join(manifest_dir, "matching-statefulset.yaml")).fetch(0)
service = load_documents(File.join(manifest_dir, "matching-headless-service.yaml")).fetch(0)
pdb = load_documents(File.join(manifest_dir, "matching-pod-disruption-budget.yaml")).fetch(0)
gateway_statefulset = load_documents(File.join(manifest_dir, "quickfix-gateway-statefulset.yaml")).fetch(0)
gateway_service = load_documents(File.join(manifest_dir, "quickfix-gateway-owner-0-service.yaml")).fetch(0)
rbac_documents = load_documents(File.join(manifest_dir, "matching-lease-rbac.yaml"))
leases = load_documents(File.join(manifest_dir, "matching-partition-leases.yaml"))
oci_patch = JSON.parse(File.read(File.join(manifest_dir, "matching-artifact-oci-data-image-patch.json"), encoding: "UTF-8"))

expected_lease_names = (0...15).map { |partition| format("matching-partition-%02d", partition) }

require_value(statefulset.fetch("kind") == "StatefulSet", "matching workload must be a StatefulSet")
require_value(statefulset.dig("metadata", "name") == "matching", "matching StatefulSet name must be matching")
require_value(statefulset.dig("spec", "replicas") == 15, "matching StatefulSet must have 15 replicas")
require_value(statefulset.dig("spec", "serviceName") == "matching-headless", "matching StatefulSet must use the headless Service")
require_value(gateway_statefulset.fetch("kind") == "StatefulSet", "Gateway workload must be a StatefulSet")
require_value(gateway_statefulset.dig("metadata", "name") == "quickfix-gateway", "Gateway StatefulSet name must be quickfix-gateway")
require_value(gateway_statefulset.dig("spec", "replicas") == 1, "Phase 1 Gateway must have one owner")
require_value(gateway_service.fetch("kind") == "Service", "Gateway owner Service must be present")
require_value(
  gateway_statefulset.dig("spec", "template", "spec", "containers", 0, "image").include?("@sha256:"),
  "Gateway image must be digest pinned"
)

gateway_container = gateway_statefulset.dig("spec", "template", "spec", "containers", 0)
gateway_environment = gateway_container.fetch("env").to_h { |entry| [entry.fetch("name"), entry] }
require_value(
  gateway_environment.dig("SIMPLEMATCH_KUBERNETES_CONFIG_IMPORT", "value") == "kubernetes:",
  "Gateway Kubernetes Config Import must be a valid Spring location"
)

template_spec = statefulset.dig("spec", "template", "spec")
container = template_spec.fetch("containers").find { |candidate| candidate.fetch("name") == "matching" }
require_value(!container.nil?, "matching StatefulSet must contain the matching container")

environment = container.fetch("env").to_h { |entry| [entry.fetch("name"), entry] }
require_value(
  environment.dig("MATCHING_PARTITION_ID", "valueFrom", "fieldRef", "fieldPath") ==
    "metadata.labels['apps.kubernetes.io/pod-index']",
  "matching partition must come from the StatefulSet pod-index label"
)
require_value(
  environment.dig("MATCHING_LEASE_UNCERTAINTY_FENCE_SECONDS", "value") == "5",
  "matching must self-fence after five seconds of Lease uncertainty"
)
require_value(
  environment.dig("MATCHING_ARTIFACT_PATH", "value") == "/etc/simplematch/market-reference/market_reference.json",
  "matching must use the canonical artifact path"
)
require_value(
  environment.dig("MATCHING_ARTIFACT_CHECKSUM_PATH", "value") == "/etc/simplematch/market-reference/market_reference.sha256",
  "matching must use the independently mounted artifact checksum"
)

resources = container.fetch("resources")
require_value(resources.dig("requests", "cpu") == "3", "matching CPU request must be three cores")
require_value(resources.dig("limits", "cpu") == "3", "matching CPU limit must be three cores")
require_value(
  resources.dig("requests", "memory") == resources.dig("limits", "memory"),
  "matching memory request and limit must match for Guaranteed QoS"
)

claim = statefulset.dig("spec", "volumeClaimTemplates").find { |candidate| candidate.dig("metadata", "name") == "matching-baseline" }
require_value(!claim.nil?, "matching must declare an ordinal baseline PVC")
require_value(claim.dig("spec", "accessModes") == ["ReadWriteOncePod"], "matching PVC must use ReadWriteOncePod only")

artifact_volume = template_spec.fetch("volumes").find { |volume| volume.fetch("name") == "market-reference" }
require_value(
  artifact_volume.dig("configMap", "name") == "matching-daily-artifact" &&
    artifact_volume.dig("configMap", "items", 0, "key") == "market_reference.json" &&
    artifact_volume.dig("configMap", "items", 1, "key") == "market_reference.sha256",
  "default matching artifact source must be the reviewed immutable ConfigMap"
)

require_value(service.fetch("kind") == "Service" && service.dig("spec", "clusterIP") == "None", "matching must expose a headless Service")
require_value(pdb.fetch("kind") == "PodDisruptionBudget" && pdb.dig("spec", "maxUnavailable") == 1, "matching PDB must allow at most one unavailable pod")

role = rbac_documents.find { |document| document.fetch("kind") == "Role" }
lease_rule = role.fetch("rules").find { |rule| rule.fetch("resources") == ["leases"] }
require_value(lease_rule.fetch("resourceNames").sort == expected_lease_names, "matching Lease RBAC must name all and only the 15 fixed leases")
require_value(lease_rule.fetch("verbs").sort == %w[get patch update], "matching Lease RBAC must not create arbitrary leases")

require_value(leases.length == 15, "matching must pre-create exactly 15 partition Leases")
require_value(leases.map { |lease| lease.dig("metadata", "name") }.sort == expected_lease_names, "partition Lease names must map 00 through 14")
require_value(leases.all? { |lease| lease.fetch("kind") == "Lease" && lease.dig("spec", "leaseDurationSeconds") == 15 }, "partition Leases must use the documented 15-second duration")

replace_artifact_volume = oci_patch.find { |operation| operation.fetch("op") == "replace" && operation.fetch("path") == "/spec/template/spec/volumes/0" }
require_value(replace_artifact_volume&.dig("value", "name") == "market-reference" && replace_artifact_volume.dig("value", "emptyDir") == {}, "OCI artifact overlay must replace the ConfigMap volume at the canonical path")
add_init_container = oci_patch.find { |operation| operation.fetch("op") == "add" && operation.fetch("path") == "/spec/template/spec/initContainers" }
require_value(add_init_container&.dig("value", 0, "name") == "artifact-data-image", "OCI artifact overlay must populate the canonical volume through an init container")
require_value(
  add_init_container&.dig("value", 0, "command", 2).include?("market_reference.sha256"),
  "OCI artifact overlay must copy the independently mounted checksum"
)
RUBY

echo "Matching Kubernetes manifest checks passed."
