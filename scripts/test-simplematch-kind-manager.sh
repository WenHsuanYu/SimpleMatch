#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd -- "$script_dir/.." && pwd)"
manager="$script_dir/manage-simplematch-live.sh"

command -v ruby >/dev/null 2>&1 || { printf '%s\n' 'ruby is required.' >&2; exit 1; }
bash -n "$manager"

ruby -ryaml - "$repo_root/deploy/kind/simplematch-live.yaml" "$repo_root/deploy/kind/simplematch-live-storageclass.yaml" <<'RUBY'
cluster_path, storage_path = ARGV
cluster = YAML.load_file(cluster_path)
storage = YAML.load_file(storage_path)
abort "unexpected cluster name" unless cluster.fetch("name") == "simplematch-live"
nodes = cluster.fetch("nodes")
workers = nodes.select { |node| node.fetch("role") == "worker" }
abort "canonical cluster must have three workers" unless workers.length == 3
abort "canonical cluster must have one control plane" unless nodes.count { |node| node.fetch("role") == "control-plane" } == 1
abort "worker slots are not stable" unless workers.map { |node| node.dig("labels", "simplematch.io/worker-slot") }.sort == %w[0 1 2]
abort "worker pool label is missing" unless workers.all? { |node| node.dig("labels", "simplematch.io/node-pool") == "local-resilience" }
abort "StorageClass kind is wrong" unless storage.fetch("kind") == "StorageClass"
abort "StorageClass name is wrong" unless storage.dig("metadata", "name") == "simplematch-rwo-pod"
abort "StorageClass must wait for first consumer" unless storage.fetch("volumeBindingMode") == "WaitForFirstConsumer"
abort "StorageClass must delete run-owned PVs" unless storage.fetch("reclaimPolicy") == "Delete"
RUBY

for operation in create verify delete; do
  output="$($manager "$operation" --dry-run)"
  grep -Fq 'DRY RUN:' <<<"$output"
done

grep -Fq 'refusing to modify existing kind cluster' "$manager"
grep -Fq "kind delete cluster --name \"\$cluster_name\"" "$manager"
grep -Fq 'PV node affinity' "$manager"
grep -Fq "docker inspect \"\$node\"" "$manager"
grep -Fq 'io.x-k8s.kind.cluster' "$manager"
grep -Fq 'verify_identity' "$manager"

printf '%s\n' 'Canonical kind cluster manager contract passed.'
