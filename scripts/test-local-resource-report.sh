#!/usr/bin/env bash
set -Eeuo pipefail
IFS=$'\n\t'
trap 'printf "Local resource contract failed at line %s\n" "$LINENO" >&2' ERR

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/lib/local-common.sh
source "$script_dir/lib/local-common.sh"
# shellcheck source=scripts/lib/local-resource.sh
source "$script_dir/lib/local-resource.sh"

command -v jq >/dev/null 2>&1 || { printf '%s\n' 'jq is required' >&2; exit 1; }

baseline="$(mktemp "${TMPDIR:-/tmp}/simplematch-resource-baseline-test.XXXXXX.json")"
current="$(mktemp "${TMPDIR:-/tmp}/simplematch-resource-current-test.XXXXXX.json")"
active="$(mktemp "${TMPDIR:-/tmp}/simplematch-resource-active-test.XXXXXX.json")"
mismatch="$(mktemp "${TMPDIR:-/tmp}/simplematch-resource-mismatch-test.XXXXXX.json")"
malformed="$(mktemp "${TMPDIR:-/tmp}/simplematch-resource-malformed-test.XXXXXX.json")"
comparison_file="$(mktemp "${TMPDIR:-/tmp}/simplematch-resource-comparison-test.XXXXXX.json")"
trap 'rm -f "$baseline" "$current" "$active" "$mismatch" "$malformed" "$comparison_file"' EXIT

cat >"$baseline" <<'JSON'
{
  "schema_version":1,
  "generated_at_utc":"2026-08-21T00:00:00Z",
  "cluster":"simplematch-live",
  "registry":{"size_bytes":1000},
  "kind":{
    "present":true,
    "cluster_fingerprint":"generation-a",
    "nodes":[
      {"name":"simplematch-live-control-plane","container_id":"cp","containerd_bytes":40,"content_bytes":15,"overlayfs_bytes":25,"exited_containers":0,"notready_sandboxes":0},
      {"name":"simplematch-live-worker","container_id":"w1","containerd_bytes":60,"content_bytes":25,"overlayfs_bytes":35,"exited_containers":0,"notready_sandboxes":0}
    ],
    "totals":{"containerd_bytes":100,"content_bytes":40,"overlayfs_bytes":60,"exited_containers":0,"notready_sandboxes":0}
  },
  "kubernetes":{"disposable_namespaces":[],"non_baseline_pods":[],"pv_count":0}
}
JSON

cat >"$current" <<'JSON'
{
  "schema_version":1,
  "generated_at_utc":"2026-08-21T01:00:00Z",
  "cluster":"simplematch-live",
  "registry":{"size_bytes":1300},
  "kind":{
    "present":true,
    "cluster_fingerprint":"generation-a",
    "nodes":[
      {"name":"simplematch-live-control-plane","container_id":"cp","containerd_bytes":45,"content_bytes":17,"overlayfs_bytes":28,"exited_containers":1,"notready_sandboxes":0},
      {"name":"simplematch-live-worker","container_id":"w1","containerd_bytes":75,"content_bytes":33,"overlayfs_bytes":42,"exited_containers":2,"notready_sandboxes":1}
    ],
    "totals":{"containerd_bytes":120,"content_bytes":50,"overlayfs_bytes":70,"exited_containers":3,"notready_sandboxes":1}
  },
  "kubernetes":{"disposable_namespaces":[],"non_baseline_pods":[],"pv_count":0}
}
JSON

simplematch_local_resource_validate_snapshot_file "$baseline"
simplematch_local_resource_assert_clean_baseline_json "$baseline"
if simplematch_local_resource_require_collection_dependencies >/dev/null 2>&1; then
  printf '%s\n' 'collection dependencies unexpectedly implicit' >&2
  exit 1
fi

rendered_snapshot="$(simplematch_local_resource_render_snapshot_file "$current")"
grep -Fq 'cluster_fingerprint=generation-a' <<<"$rendered_snapshot"
grep -Fq 'registry_bytes=1300' <<<"$rendered_snapshot"
grep -Fq 'node=simplematch-live-worker containerd_bytes=75' <<<"$rendered_snapshot"

comparison="$(simplematch_local_resource_compare_files "$baseline" "$current")"
[[ "$(jq -r .assessment <<<"$comparison")" == IDLE_RESIDUAL_GROWTH ]]
[[ "$(jq -r .cluster_idle <<<"$comparison")" == true ]]
[[ "$(jq -r .recycle_candidate <<<"$comparison")" == true ]]
[[ "$(jq -r .deltas.containerd_bytes <<<"$comparison")" == 20 ]]
[[ "$(jq -r .deltas.content_bytes <<<"$comparison")" == 10 ]]
[[ "$(jq -r .deltas.overlayfs_bytes <<<"$comparison")" == 10 ]]
[[ "$(jq -r .deltas.registry_bytes <<<"$comparison")" == 300 ]]
[[ "$(jq -r '.nodes[] | select(.name=="simplematch-live-worker") | .containerd_bytes' <<<"$comparison")" == 15 ]]
printf '%s\n' "$comparison" >"$comparison_file"
rendered_comparison="$(simplematch_local_resource_render_comparison_file "$comparison_file")"
grep -Fq 'assessment=IDLE_RESIDUAL_GROWTH' <<<"$rendered_comparison"
grep -Fq 'recycle_candidate=true' <<<"$rendered_comparison"
grep -Fq 'registry_delta_bytes=300' <<<"$rendered_comparison"

jq '.kubernetes.non_baseline_pods=[{"namespace":"work","name":"pod","phase":"Running"}]' "$current" >"$active"
comparison="$(simplematch_local_resource_compare_files "$baseline" "$active")"
[[ "$(jq -r .assessment <<<"$comparison")" == ACTIVE_WORKLOAD_GROWTH ]]
[[ "$(jq -r .cluster_idle <<<"$comparison")" == false ]]
[[ "$(jq -r .recycle_candidate <<<"$comparison")" == false ]]

jq '.kubernetes.pv_count=1' "$current" >"$active"
comparison="$(simplematch_local_resource_compare_files "$baseline" "$active")"
[[ "$(jq -r .assessment <<<"$comparison")" == ACTIVE_WORKLOAD_GROWTH ]]
[[ "$(jq -r .cluster_idle <<<"$comparison")" == false ]]
[[ "$(jq -r .recycle_candidate <<<"$comparison")" == false ]]

jq '.kind.cluster_fingerprint="generation-b"' "$current" >"$mismatch"
if simplematch_local_resource_compare_files "$baseline" "$mismatch" >/dev/null 2>&1; then
  printf '%s\n' 'mismatched cluster generation unexpectedly accepted' >&2
  exit 1
fi

jq 'del(.kind.nodes[1]) | .kind.totals={containerd_bytes:45,content_bytes:17,overlayfs_bytes:28,exited_containers:1,notready_sandboxes:0}' "$current" >"$mismatch"
if simplematch_local_resource_compare_files "$baseline" "$mismatch" >/dev/null 2>&1; then
  printf '%s\n' 'mismatched node set unexpectedly accepted' >&2
  exit 1
fi

jq 'del(.kubernetes.pv_count)' "$current" >"$malformed"
if simplematch_local_resource_validate_snapshot_file "$malformed" >/dev/null 2>&1; then
  printf '%s\n' 'malformed snapshot unexpectedly accepted' >&2
  exit 1
fi
if simplematch_local_resource_compare_files "$baseline" "$malformed" >/dev/null 2>&1; then
  printf '%s\n' 'malformed comparison input unexpectedly accepted' >&2
  exit 1
fi

jq '.kind.totals.containerd_bytes=121' "$current" >"$malformed"
if simplematch_local_resource_validate_snapshot_file "$malformed" >/dev/null 2>&1; then
  printf '%s\n' 'inconsistent totals unexpectedly accepted' >&2
  exit 1
fi

jq '.kind.nodes[1].name=.kind.nodes[0].name' "$current" >"$malformed"
if simplematch_local_resource_validate_snapshot_file "$malformed" >/dev/null 2>&1; then
  printf '%s\n' 'duplicate node name unexpectedly accepted' >&2
  exit 1
fi

jq '.kind.nodes[1].container_id=.kind.nodes[0].container_id' "$current" >"$malformed"
if simplematch_local_resource_validate_snapshot_file "$malformed" >/dev/null 2>&1; then
  printf '%s\n' 'duplicate node container identity unexpectedly accepted' >&2
  exit 1
fi

jq '.kind.nodes[0].containerd_bytes=-1 | .kind.totals.containerd_bytes=79' "$current" >"$malformed"
if simplematch_local_resource_validate_snapshot_file "$malformed" >/dev/null 2>&1; then
  printf '%s\n' 'negative resource measurement unexpectedly accepted' >&2
  exit 1
fi

jq '.kind.totals.containerd_bytes=90 | .kind.totals.content_bytes=35 | .kind.totals.overlayfs_bytes=55 | .kind.nodes[0].containerd_bytes=35 | .kind.nodes[0].content_bytes=12 | .kind.nodes[0].overlayfs_bytes=23 | .kind.nodes[1].containerd_bytes=55 | .kind.nodes[1].content_bytes=23 | .kind.nodes[1].overlayfs_bytes=32' "$current" >"$active"
comparison="$(simplematch_local_resource_compare_files "$baseline" "$active")"
[[ "$(jq -r .assessment <<<"$comparison")" == NO_CONTAINERD_GROWTH ]]
[[ "$(jq -r .recycle_candidate <<<"$comparison")" == false ]]

jq '.registry.size_bytes=null' "$current" >"$active"
rendered_snapshot="$(simplematch_local_resource_render_snapshot_file "$active")"
grep -Fq 'registry_bytes=unknown' <<<"$rendered_snapshot"
comparison="$(simplematch_local_resource_compare_files "$baseline" "$active")"
printf '%s\n' "$comparison" >"$comparison_file"
rendered_comparison="$(simplematch_local_resource_render_comparison_file "$comparison_file")"
grep -Fq 'registry_delta_bytes=unknown' <<<"$rendered_comparison"

printf '%s\n' 'Local resource baseline/growth contract passed.'
