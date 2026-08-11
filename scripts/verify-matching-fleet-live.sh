#!/usr/bin/env bash
set -euo pipefail

namespace="${SIMPLEMATCH_NAMESPACE:-production}"
statefulset_name="${SIMPLEMATCH_MATCHING_STATEFULSET:-matching}"
kubectl_bin="${KUBECTL:-kubectl}"

usage() {
  printf '%s\n' \
    'Usage: verify-matching-fleet-live.sh [options]' \
    '' \
    '  --namespace NAME       Kubernetes namespace (default: SIMPLEMATCH_NAMESPACE or production).' \
    '  --statefulset NAME     Matching StatefulSet (default: SIMPLEMATCH_MATCHING_STATEFULSET or matching).' \
    '  --kubectl PATH         kubectl executable (default: KUBECTL or kubectl).' \
    '' \
    'The gate is intentionally strict: it requires 15 Ready pods, 15 distinct nodes,' \
    '15 current Lease holders, 15 Bound ReadWriteOncePod PVCs, and real digest-pinned images.'
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --namespace) namespace="$2"; shift 2 ;;
    --statefulset) statefulset_name="$2"; shift 2 ;;
    --kubectl) kubectl_bin="$2"; shift 2 ;;
    --help|-h) usage; exit 0 ;;
    *) usage >&2; printf 'Unknown option: %s\n' "$1" >&2; exit 2 ;;
  esac
done

command -v "$kubectl_bin" >/dev/null 2>&1 || {
  printf 'Cannot find kubectl executable: %s\n' "$kubectl_bin" >&2
  exit 1
}
command -v ruby >/dev/null 2>&1 || {
  printf '%s\n' 'Ruby is required for Kubernetes JSON validation.' >&2
  exit 1
}

temporary_directory="$(mktemp -d)"
trap 'rm -rf "$temporary_directory"' EXIT

"$kubectl_bin" -n "$namespace" get statefulset "$statefulset_name" -o json \
  > "$temporary_directory/statefulset.json"
"$kubectl_bin" -n "$namespace" get pods \
  -l app.kubernetes.io/name=matching -o json \
  > "$temporary_directory/pods.json"
"$kubectl_bin" -n "$namespace" get leases \
  -l app.kubernetes.io/name=matching -o json \
  > "$temporary_directory/leases.json"
"$kubectl_bin" -n "$namespace" get pvc -o json \
  > "$temporary_directory/pvcs.json"

NAMESPACE="$namespace" STATEFULSET_NAME="$statefulset_name" ruby -rjson -rtime - \
  "$temporary_directory/statefulset.json" \
  "$temporary_directory/pods.json" \
  "$temporary_directory/leases.json" \
  "$temporary_directory/pvcs.json" <<'RUBY'
namespace = ENV.fetch("NAMESPACE")
statefulset_name = ENV.fetch("STATEFULSET_NAME")
statefulset, pods, leases, pvcs = ARGV.map { |path| JSON.parse(File.read(path)) }

def fail_gate(message)
  warn message
  exit 1
end

def require_gate(condition, message)
  fail_gate(message) unless condition
end

def items(resource)
  Array(resource.fetch("items"))
end

def ready?(pod)
  Array(pod.dig("status", "conditions")).any? do |condition|
    condition["type"] == "Ready" && condition["status"] == "True"
  end
end

expected_partitions = (0...15).to_a
expected_pod_names = expected_partitions.map { |partition| "#{statefulset_name}-#{partition}" }
expected_lease_names = expected_partitions.map { |partition| format("matching-partition-%02d", partition) }

require_gate(
  statefulset.dig("spec", "replicas") == 15,
  "#{namespace}/#{statefulset_name} declares #{statefulset.dig("spec", "replicas") || "no"} replicas; expected 15"
)

matching_pods = items(pods).select do |pod|
  pod.dig("metadata", "labels", "app.kubernetes.io/name") == "matching"
end
require_gate(matching_pods.length == 15, "expected 15 Matching pods, got #{matching_pods.length}")
require_gate(
  matching_pods.map { |pod| pod.dig("metadata", "name") }.sort == expected_pod_names,
  "Matching pod names must be #{expected_pod_names.join(", ")}"
)
require_gate(
  matching_pods.all? { |pod| ready?(pod) },
  "every Matching pod must have Ready=True"
)
require_gate(
  matching_pods.map { |pod| pod.dig("spec", "nodeName") }.uniq.length == 15,
  "the 15 Matching pods must be scheduled on 15 distinct nodes because pod anti-affinity is required"
)
pod_partitions = matching_pods.map { |pod| pod.dig("metadata", "labels", "apps.kubernetes.io/pod-index") }
require_gate(
  pod_partitions.sort == expected_partitions.map(&:to_s),
  "Matching pod-index labels must cover every partition from 0 through 14 exactly once"
)

matching_pods.each do |pod|
  image = pod.dig("spec", "containers", 0, "image").to_s
  require_gate(
    image.match?(/@sha256:[0-9a-f]{64}\z/i),
    "#{pod.dig("metadata", "name")} image is not digest pinned: #{image}"
  )
  require_gate(
    !image.match?(/@sha256:a{64}\z/i),
    "#{pod.dig("metadata", "name")} still uses the repository placeholder image digest"
  )
end

lease_by_name = items(leases).to_h { |lease| [lease.dig("metadata", "name"), lease] }
require_gate(
  lease_by_name.keys.sort == expected_lease_names,
  "expected exactly the 15 fixed Matching Leases"
)

matching_pods.each do |pod|
  pod_name = pod.dig("metadata", "name")
  partition = pod.dig("metadata", "labels", "apps.kubernetes.io/pod-index")
  require_gate(partition && partition.match?(/\A(?:[0-9]|1[0-4])\z/), "#{pod_name} has no valid pod-index 0-14")
  lease_name = format("matching-partition-%02d", Integer(partition))
  lease = lease_by_name.fetch(lease_name)
  spec = lease.fetch("spec")
  require_gate(spec.fetch("leaseDurationSeconds") == 15, "#{lease_name} must use a 15-second Lease")
  holder = spec["holderIdentity"]
  expected_holder = "#{pod_name}:#{pod.dig("metadata", "uid")}"
  require_gate(holder == expected_holder, "#{lease_name} holder #{holder.inspect} does not fence to #{expected_holder}")
  renew_time = spec["renewTime"]
  require_gate(renew_time.is_a?(String) && !renew_time.empty?, "#{lease_name} has no renewTime")
  require_gate(
    Time.parse(renew_time) + spec.fetch("leaseDurationSeconds") > Time.now,
    "#{lease_name} is expired"
  )
end

pvcs_by_name = items(pvcs).to_h { |pvc| [pvc.dig("metadata", "name"), pvc] }
expected_partitions.each do |partition|
  pvc_name = "matching-baseline-#{statefulset_name}-#{partition}"
  pvc = pvcs_by_name.fetch(pvc_name) do
    fail_gate("missing PVC #{pvc_name}")
  end
  require_gate(pvc.dig("status", "phase") == "Bound", "#{pvc_name} is not Bound")
  require_gate(
    Array(pvc.dig("spec", "accessModes")).include?("ReadWriteOncePod"),
    "#{pvc_name} must use ReadWriteOncePod"
  )
end

puts "Matching fleet certification passed: 15 Ready pods, 15 Lease holders, 15 RWOP PVCs, and 15 distinct nodes in #{namespace}."
RUBY
