#!/usr/bin/env bash
set -Eeuo pipefail
IFS=$'\n\t'

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd -- "$script_dir/.." && pwd)"
# shellcheck source=scripts/lib/local-common.sh
source "$script_dir/lib/local-common.sh"
# shellcheck source=scripts/lib/local-kind.sh
source "$script_dir/lib/local-kind.sh"
# shellcheck source=scripts/lib/local-registry.sh
source "$script_dir/lib/local-registry.sh"
# shellcheck source=scripts/lib/local-resource.sh
source "$script_dir/lib/local-resource.sh"

cluster_name="${SIMPLEMATCH_KIND_CLUSTER_NAME:-simplematch-live}"
baseline_file="${SIMPLEMATCH_LOCAL_RESOURCE_BASELINE_FILE:-$repo_root/out/local-resource-baseline.json}"
snapshot_attempts="${SIMPLEMATCH_LOCAL_RESOURCE_SNAPSHOT_ATTEMPTS:-3}"
output_file=""
write_baseline_file=""
compare_baseline_file=""
json_only=false
no_baseline=false

temp_snapshot=""
cleanup() {
  if [[ -n "$temp_snapshot" ]]; then
    rm -f "$temp_snapshot"
  fi
}
trap cleanup EXIT

usage() {
  cat <<'EOF_USAGE'
Usage:
  scripts/local-resource-report.sh [options]

Options:
  --cluster NAME           kind cluster name (default: simplematch-live).
  --output FILE            Save the current machine-readable snapshot to FILE.
  --write-baseline FILE    Require a clean/idle cluster and write FILE as its baseline.
  --baseline FILE          Compare the current snapshot with FILE.
  --no-baseline            Do not auto-compare the default baseline when it exists.
  --json                   Print only the current snapshot JSON.
  -h, --help               Show this help.

The default baseline path is out/local-resource-baseline.json (override with
SIMPLEMATCH_LOCAL_RESOURCE_BASELINE_FILE). A baseline is valid only for the exact
kind cluster generation that created it; node Docker container IDs form the
cluster fingerprint.

Snapshot collection is retried as a whole for transient Docker/kubelet/containerd
measurement races. Override the bounded attempt count with
SIMPLEMATCH_LOCAL_RESOURCE_SNAPSHOT_ATTEMPTS (default: 3).

A baseline may be written only when there are no disposable namespaces, no Pods
outside kube-system/local-path-storage, and no PVs. Comparison never uses a fixed
GB threshold. IDLE_RESIDUAL_GROWTH means the reusable cluster is idle but its
containerd storage remains above the clean baseline, making cluster recycle a
measurable candidate rather than an automatic action.
EOF_USAGE
}

while (($# > 0)); do
  case "$1" in
    --cluster)
      cluster_name="${2:?--cluster requires a value}"
      shift 2
      ;;
    --output)
      output_file="${2:?--output requires a file}"
      shift 2
      ;;
    --write-baseline)
      write_baseline_file="${2:?--write-baseline requires a file}"
      shift 2
      ;;
    --baseline)
      compare_baseline_file="${2:?--baseline requires a file}"
      shift 2
      ;;
    --no-baseline)
      no_baseline=true
      shift
      ;;
    --json)
      json_only=true
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      usage >&2
      exit 2
      ;;
  esac
done

[[ "$snapshot_attempts" =~ ^[1-9][0-9]*$ ]] ||
  simplematch_die "SIMPLEMATCH_LOCAL_RESOURCE_SNAPSHOT_ATTEMPTS must be a positive integer: $snapshot_attempts"

simplematch_require_command docker
simplematch_require_command kind
simplematch_require_command kubectl
simplematch_require_command jq

docker info >/dev/null 2>&1 || simplematch_die 'Docker daemon is not reachable'

temp_snapshot="$(mktemp "${TMPDIR:-/tmp}/simplematch-local-resource.XXXXXX.json")"
collect_snapshot() {
  local attempt
  for ((attempt = 1; attempt <= snapshot_attempts; attempt++)); do
    : >"$temp_snapshot"
    if simplematch_local_resource_snapshot "$cluster_name" >"$temp_snapshot"; then
      return 0
    fi
    if ((attempt < snapshot_attempts)); then
      simplematch_warn "resource snapshot collection failed (${attempt}/${snapshot_attempts}); retrying"
      sleep 1
    fi
  done
  simplematch_die "resource snapshot collection failed after ${snapshot_attempts} attempts"
}

collect_snapshot
jq -e '.schema_version == 1' "$temp_snapshot" >/dev/null || simplematch_die 'resource snapshot is malformed'

if [[ -n "$output_file" ]]; then
  mkdir -p "$(dirname -- "$output_file")"
  cp "$temp_snapshot" "$output_file"
fi

if [[ -n "$write_baseline_file" ]]; then
  if ! simplematch_local_resource_assert_clean_baseline_json "$temp_snapshot"; then
    simplematch_die 'refusing to establish baseline: cluster is not clean/idle or still owns PV state'
  fi
  mkdir -p "$(dirname -- "$write_baseline_file")"
  cp "$temp_snapshot" "$write_baseline_file"
fi

if [[ "$json_only" == true ]]; then
  cat "$temp_snapshot"
  exit 0
fi

simplematch_log 'Current local resource snapshot'
simplematch_local_resource_render_snapshot_file "$temp_snapshot"

if [[ -n "$write_baseline_file" ]]; then
  simplematch_info "Established clean resource baseline: $write_baseline_file"
fi

if [[ -z "$compare_baseline_file" && "$no_baseline" == false && -f "$baseline_file" ]]; then
  compare_baseline_file="$baseline_file"
fi

if [[ -n "$compare_baseline_file" ]]; then
  [[ -f "$compare_baseline_file" ]] || simplematch_die "baseline does not exist: $compare_baseline_file"
  comparison_file="$(mktemp "${TMPDIR:-/tmp}/simplematch-local-resource-comparison.XXXXXX.json")"
  if ! simplematch_local_resource_compare_files "$compare_baseline_file" "$temp_snapshot" >"$comparison_file"; then
    rm -f "$comparison_file"
    simplematch_die 'resource baseline cannot be compared with the current cluster generation'
  fi
  simplematch_log "Growth relative to baseline: $compare_baseline_file"
  simplematch_local_resource_render_comparison_file "$comparison_file"
  rm -f "$comparison_file"
fi
