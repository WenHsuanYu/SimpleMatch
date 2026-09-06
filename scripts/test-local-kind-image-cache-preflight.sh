#!/usr/bin/env bash
set -Eeuo pipefail
IFS=$'\n\t'

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/lib/local-resilience.sh
source "$script_dir/lib/local-resilience.sh"

fail() {
  printf 'image-cache preflight contract failed: %s\n' "$*" >&2
  exit 1
}

fixture_dir="$(mktemp -d "${TMPDIR:-/tmp}/simplematch-image-cache-preflight.XXXXXX")"
original_path="$PATH"
trap 'PATH="$original_path"; export PATH; rm -rf -- "$fixture_dir"' EXIT
mock_bin="$fixture_dir/bin"
mkdir -p "$mock_bin"

for command_name in date dirname jq mkdir timeout; do
  ln -s "$(command -v "$command_name")" "$mock_bin/$command_name"
done

nodes_file="$fixture_dir/nodes.json"
cat >"$nodes_file" <<'EOF_NODES'
{"items":[
  {"metadata":{"name":"simplematch-live-worker","labels":{"simplematch.io/node-pool":"local-resilience"}},"spec":{"unschedulable":false,"taints":[]},"status":{"conditions":[{"type":"Ready","status":"True"}]}},
  {"metadata":{"name":"simplematch-live-worker2","labels":{"simplematch.io/node-pool":"local-resilience"}},"spec":{"unschedulable":false,"taints":[]},"status":{"conditions":[{"type":"Ready","status":"True"}]}},
  {"metadata":{"name":"unrelated-worker","labels":{"simplematch.io/node-pool":"other"}},"spec":{"unschedulable":false,"taints":[]},"status":{"conditions":[{"type":"Ready","status":"True"}]}}
]}
EOF_NODES

workload_file="$fixture_dir/connect-deployment.json"
cat >"$workload_file" <<'EOF_WORKLOAD'
{"spec":{"template":{"spec":{"nodeSelector":{"simplematch.io/node-pool":"local-resilience"},"containers":[{"name":"kafka-connect","image":"quay.io/debezium/connect:3.6.0.Final"}]}}}}
EOF_WORKLOAD

cat >"$mock_bin/kubectl" <<'EOF_KUBECTL'
#!/bin/bash
set -euo pipefail
if [[ "$*" == *'get nodes -o json'* ]]; then
  /bin/cat "$MOCK_NODES_FILE"
  exit 0
fi
printf 'unexpected kubectl invocation: %s\n' "$*" >&2
exit 2
EOF_KUBECTL
chmod +x "$mock_bin/kubectl"

cat >"$mock_bin/docker" <<'EOF_DOCKER'
#!/bin/bash
set -euo pipefail
[[ "${1:-}" == exec && -n "${2:-}" ]] || exit 2
node="$2"
case "${3:-}" in
  crictl)
    identity="${MOCK_WORKER_A_IDENTITY}"
    [[ "$node" == simplematch-live-worker2 ]] && identity="$MOCK_WORKER_B_IDENTITY"
    printf '{"status":{"id":"%s"}}\n' "$identity"
    ;;
  ctr)
    [[ "$node" == "${MOCK_FAIL_NODE:-}" ]] && exit 42
    true
    ;;
  *)
    printf 'unexpected docker invocation: %s\n' "$*" >&2
    exit 2
    ;;
esac
EOF_DOCKER
chmod +x "$mock_bin/docker"

PATH="$mock_bin:$original_path"
export PATH MOCK_NODES_FILE="$nodes_file"
SIMPLEMATCH_KIND_IMAGE_CACHE_PREFLIGHT_DEFAULT_SECONDS=10
export SIMPLEMATCH_KIND_IMAGE_CACHE_PREFLIGHT_DEFAULT_SECONDS
MOCK_WORKER_A_IDENTITY='sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'
MOCK_WORKER_B_IDENTITY="$MOCK_WORKER_A_IDENTITY"
MOCK_FAIL_NODE=''
export MOCK_WORKER_A_IDENTITY MOCK_WORKER_B_IDENTITY MOCK_FAIL_NODE

pass_evidence="$fixture_dir/pass.json"
simplematch_kind_image_cache_preflight kind-test \
  "$workload_file" "$pass_evidence" ||
  fail 'identical executable node images were rejected'
[[ "$(jq -r '.status' "$pass_evidence")" == PASS ]] || fail 'pass status missing'
[[ "$(jq -r '.nodes | length' "$pass_evidence")" == 2 ]] || fail 'both nodes were not checked'
[[ "$(jq -r '.nodes | map(.execution_probe_status) | unique | .[0]' "$pass_evidence")" == PASS ]] ||
  fail 'execution probe result was not retained'
[[ "$(jq -r '.identity_source' "$pass_evidence")" == node-containerd ]] ||
  fail 'tag reference did not retain node identity source'

MOCK_WORKER_B_IDENTITY='sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb'
export MOCK_WORKER_B_IDENTITY
mismatch_evidence="$fixture_dir/mismatch.json"
if simplematch_kind_image_cache_preflight kind-test \
    "$workload_file" "$mismatch_evidence"; then
  fail 'different node identities were accepted'
fi
[[ "$(jq -r '.status' "$mismatch_evidence")" == FAILED ]] || fail 'identity mismatch was not retained as failure'

MOCK_WORKER_B_IDENTITY="$MOCK_WORKER_A_IDENTITY"
MOCK_FAIL_NODE=simplematch-live-worker2
export MOCK_WORKER_B_IDENTITY MOCK_FAIL_NODE
probe_evidence="$fixture_dir/probe-failure.json"
if simplematch_kind_image_cache_preflight kind-test \
    "$workload_file" "$probe_evidence"; then
  fail 'failed containerd execution probe was accepted'
fi
[[ "$(jq -r '.nodes[1].execution_probe_status' "$probe_evidence")" == FAILED ]] ||
  fail 'failed execution probe was not retained'

MOCK_FAIL_NODE=''
export MOCK_FAIL_NODE
pinned_evidence="$fixture_dir/pinned.json"
pinned_workload="$fixture_dir/pinned-deployment.json"
jq --arg image "quay.io/debezium/connect@$MOCK_WORKER_A_IDENTITY" \
  '.spec.template.spec.containers[0].image = $image' "$workload_file" >"$pinned_workload"
simplematch_kind_image_cache_preflight kind-test \
  "$pinned_workload" \
  "$pinned_evidence" ||
  fail 'canonical digest-pinned image was rejected'
[[ "$(jq -r '.identity_source' "$pinned_evidence")" == node-containerd ]] ||
  fail 'digest-pinned node identity source was not retained'

invalid_evidence="$fixture_dir/invalid-digest.json"
invalid_workload="$fixture_dir/invalid-digest-deployment.json"
jq '.spec.template.spec.containers[0].image = "quay.io/debezium/connect@sha256:not-a-digest"' \
  "$workload_file" >"$invalid_workload"
if simplematch_kind_image_cache_preflight kind-test \
    "$invalid_workload" "$invalid_evidence"; then
  fail 'malformed digest-pinned image was accepted'
fi
[[ "$(jq -r '.status' "$invalid_evidence")" == FAILED ]] ||
  fail 'malformed digest failure was not retained'

SIMPLEMATCH_KIND_IMAGE_CACHE_PREFLIGHT_DEFAULT_SECONDS=121
if simplematch_kind_image_cache_preflight kind-test \
    "$workload_file" "$fixture_dir/over-budget.json"; then
  fail 'preflight budget above the maximum was accepted'
fi

printf '%s\n' 'Local kind image-cache preflight contract passed.'
