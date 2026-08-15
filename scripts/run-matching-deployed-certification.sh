#!/usr/bin/env bash

set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd -- "$script_dir/.." && pwd)"
# shellcheck source=scripts/lib/matching-e2e.sh
source "$script_dir/lib/matching-e2e.sh"
cluster_name="${SIMPLEMATCH_KIND_CLUSTER_NAME:-simplematch-live}"
context="${SIMPLEMATCH_KUBE_CONTEXT:-kind-${cluster_name}}"
namespace="${SIMPLEMATCH_NAMESPACE:-simplematch-local}"
statefulset_name="${SIMPLEMATCH_MATCHING_STATEFULSET:-matching}"
report_path="${SIMPLEMATCH_DEPLOYED_CERTIFICATION_REPORT:-$repo_root/out/certification/matching-deployed/report.json}"
evidence_dir="$(dirname -- "$report_path")"
benchmark_binary="${SIMPLEMATCH_MATCHING_BENCHMARK_BIN:-out/build/full-native-dev/simplematch-matching-capacity-benchmark}"
require_pinned=false
dry_run=false

usage() {
  cat <<'EOF'
Usage: scripts/run-matching-deployed-certification.sh [options]

Options:
  --namespace NAME       Kubernetes namespace containing the Matching fleet.
  --context NAME         Kubernetes context (default: kind-simplematch-live).
  --cluster NAME         kind cluster identity (default: simplematch-live).
  --report PATH          JSON report path.
  --require-pinned       Require one effective CPU per Matching process and the
                         repository CPU-manager label.
  --dry-run              Print the selected targets without changing resources.

This command is read-only. It never deletes a cluster, namespace, Pod, PVC, image, or volume.
Set SIMPLEMATCH_E2E_METRICS_FILE to a separately produced JSON file when Kafka E2E, ring
occupancy, loss/duplicate, and recovery measurements are available.
EOF
}

die() {
  printf 'matching deployed certification: %s\n' "$*" >&2
  exit 2
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --namespace) namespace="${2:?--namespace requires a value}"; shift 2 ;;
    --context) context="${2:?--context requires a value}"; shift 2 ;;
    --cluster) cluster_name="${2:?--cluster requires a value}"; shift 2 ;;
    --report) report_path="${2:?--report requires a value}"; evidence_dir="$(dirname -- "$report_path")"; shift 2 ;;
    --require-pinned) require_pinned=true; shift ;;
    --dry-run) dry_run=true; shift ;;
    --help|-h) usage; exit 0 ;;
    *) usage >&2; die "unknown option: $1" ;;
  esac
done

benchmark_report="$evidence_dir/native-capacity.json"
if [[ "$benchmark_binary" = /* ]]; then
  benchmark_path="$benchmark_binary"
else
  benchmark_path="$repo_root/$benchmark_binary"
fi

if [[ "$dry_run" == true ]]; then
  printf 'DRY RUN: cluster=%s context=%s namespace=%s statefulset=%s report=%s\n' \
    "$cluster_name" "$context" "$namespace" "$statefulset_name" "$report_path"
  exit 0
fi

for tool in kind kubectl jq; do
  command -v "$tool" >/dev/null 2>&1 || die "$tool is required"
done

mkdir -p "$evidence_dir/pods"
generated_at_utc="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
status="INCOMPLETE"
failure_reason=""
missing_gates=()
deployed_status="NOT_EVALUATED"
placement_status="NOT_EVALUATED"
cpu_status="NOT_EVALUATED"
benchmark_status="NOT_EVALUATED"
e2e_status="INCOMPLETE"
runtime_evidence='[]'

if ! kind get clusters | grep -Fxq "$cluster_name"; then
  status="UNSUPPORTED"
  failure_reason="kind cluster ${cluster_name} is not available"
  missing_gates+=(deployed_cluster)
elif ! kubectl --context "$context" get nodes -o json >"$evidence_dir/nodes.json" 2>"$evidence_dir/nodes.error"; then
  status="UNSUPPORTED"
  failure_reason="Kubernetes context ${context} cannot read the cluster"
  missing_gates+=(deployed_cluster)
elif ! kubectl --context "$context" -n "$namespace" get statefulset "$statefulset_name" -o json \
  >"$evidence_dir/statefulset.json" 2>"$evidence_dir/statefulset.error"; then
  failure_reason="Matching StatefulSet ${namespace}/${statefulset_name} is not deployed"
  missing_gates+=(deployed_matching_fleet)
else
  kubectl --context "$context" -n "$namespace" get pods -l app.kubernetes.io/name=matching -o json \
    >"$evidence_dir/pods.json"
  kubectl --context "$context" -n "$namespace" get pvc -o json >"$evidence_dir/pvcs.json"
  kubectl --context "$context" get pv -o json >"$evidence_dir/pvs.json"
  kubectl --context "$context" -n "$namespace" get leases -l app.kubernetes.io/name=matching -o json \
    >"$evidence_dir/leases.json"

  pod_count="$(jq '.items | length' "$evidence_dir/pods.json")"
  ready_count="$(jq '[.items[] | select(any(.status.conditions[]?; .type == "Ready" and .status == "True"))] | length' "$evidence_dir/pods.json")"
  replicas="$(jq -r '.spec.replicas // 0' "$evidence_dir/statefulset.json")"
  if [[ "$replicas" == 15 && "$pod_count" == 15 && "$ready_count" == 15 ]]; then
    deployed_status="PASSED"
  else
    deployed_status="FAILED"
    failure_reason="Matching fleet is not 15/15 Ready (replicas=${replicas}, pods=${pod_count}, ready=${ready_count})"
  fi

  distribution="$(jq -r '[.items[] | select(.status.phase == "Running") | .spec.nodeName] | group_by(.) | map({node:.[0], count:length})' "$evidence_dir/pods.json")"
  if [[ "$deployed_status" == PASSED && "$(jq 'length' <<<"$distribution")" == 3 && \
    "$(jq -r 'map(.count) | sort | join(",")' <<<"$distribution")" == "5,5,5" ]]; then
    placement_status="PASSED"
  else
    placement_status="FAILED"
    missing_gates+=(matching_5_5_5_placement)
  fi

  cpu_evidence_complete=true
  mapfile -t pod_names < <(jq -r '.items[] | .metadata.name' "$evidence_dir/pods.json" | sort)
  for pod in "${pod_names[@]}"; do
    pod_file="$evidence_dir/pods/${pod}.json"
    kubectl --context "$context" -n "$namespace" get pod "$pod" -o json >"$pod_file"
    status_text=""
    cpu_max=""
    cpuset_effective=""
    # shellcheck disable=SC2016 # Variables expand inside the invoked container shell.
    if ! status_text="$(kubectl --context "$context" -n "$namespace" exec "$pod" -c matching -- \
      sh -ec 'for status in /proc/1/task/*/status; do
        comm="$(sed -n "s/^Name:[[:space:]]*//p" "$status")"
        if [ "$comm" = matching-writer ]; then
          cat "$status"
          exit 0
        fi
      done
      exit 1' 2>/dev/null)"; then
      cpu_evidence_complete=false
    else
      printf '%s\n' "$status_text" >"$evidence_dir/pods/${pod}.writer.status"
      cpu_max="$(kubectl --context "$context" -n "$namespace" exec "$pod" -c matching -- sh -c 'cat /sys/fs/cgroup/cpu.max 2>/dev/null || true' 2>/dev/null || true)"
      cpuset_effective="$(kubectl --context "$context" -n "$namespace" exec "$pod" -c matching -- sh -c 'cat /sys/fs/cgroup/cpuset.cpus.effective 2>/dev/null || true' 2>/dev/null || true)"
    fi
    record="$(jq -n \
      --arg pod "$pod" \
      --arg uid "$(jq -r '.metadata.uid // ""' "$pod_file")" \
      --arg node "$(jq -r '.spec.nodeName // ""' "$pod_file")" \
      --arg image_id "$(jq -r '.status.containerStatuses[]? | select(.name == "matching") | .imageID // ""' "$pod_file")" \
      --arg cpus_allowed "$(awk -F: '/^Cpus_allowed_list:/ {gsub(/^[[:space:]]+/, "", $2); print $2}' <<<"$status_text")" \
      --arg cpu_max "$cpu_max" --arg cpuset_effective "$cpuset_effective" \
      '{pod:$pod,uid:$uid,node:$node,image_id:$image_id,cpus_allowed_list:$cpus_allowed,cgroup_cpu_max:$cpu_max,cpuset_cpus_effective:$cpuset_effective}')"
    runtime_evidence="$(jq -nc --argjson current "$runtime_evidence" --argjson record "$record" '$current + [$record]')"
  done
  if [[ "$cpu_evidence_complete" == true ]]; then
    cpu_status="PASSED"
  else
    cpu_status="INCOMPLETE"
    missing_gates+=(pod_cpu_cgroup_evidence)
  fi

  if [[ "$require_pinned" == true ]]; then
    static_label_count="$(jq '[.items[] | select(.metadata.labels["simplematch.io/cpu-manager-static"] == "true")] | length' "$evidence_dir/nodes.json")"
    non_single_cpu="$(jq '[.[] | select((.cpus_allowed_list | test("^[0-9]+$")) | not)] | length' <<<"$runtime_evidence")"
    if [[ "$static_label_count" -ne 3 || "$non_single_cpu" -ne 0 ]]; then
      cpu_status="FAILED"
      failure_reason="deployed CPU pinning was required but was not proven"
    fi
  fi
fi

if [[ -x "$benchmark_path" ]]; then
  if SIMPLEMATCH_BENCHMARK_REPORT="$benchmark_report" \
    SIMPLEMATCH_REQUIRE_PINNED="$([[ "$require_pinned" == true ]] && printf true || printf false)" \
    SIMPLEMATCH_MATCHING_BENCHMARK_BIN="$benchmark_path" \
    bash "$repo_root/scripts/run-matching-capacity-certification.sh" \
    >"$evidence_dir/native-capacity.stdout" 2>"$evidence_dir/native-capacity.stderr"; then
    benchmark_status="PASSED"
  else
    benchmark_status="FAILED"
    failure_reason="native capacity benchmark failed"
  fi
else
  benchmark_status="INCOMPLETE"
  missing_gates+=(native_capacity_benchmark)
fi

if [[ -n "${SIMPLEMATCH_E2E_METRICS_FILE:-}" && -f "${SIMPLEMATCH_E2E_METRICS_FILE}" ]]; then
  if [[ "${SIMPLEMATCH_E2E_METRICS_FILE}" != "$evidence_dir/e2e-metrics.json" ]]; then
    cp "${SIMPLEMATCH_E2E_METRICS_FILE}" "$evidence_dir/e2e-metrics.json"
  fi
  if matching_e2e_report_is_valid "${SIMPLEMATCH_E2E_METRICS_FILE}" "$runtime_evidence"; then
    e2e_status="PASSED"
  else
    e2e_status="FAILED"
    failure_reason="E2E metrics report is invalid or outside the local recovery contract"
  fi
else
  missing_gates+=(kafka_e2e_ring_and_recovery_measurements)
fi

if [[ "$status" != UNSUPPORTED && "$deployed_status" == PASSED && "$placement_status" == PASSED && \
  "$cpu_status" == PASSED && "$benchmark_status" == PASSED && "$e2e_status" == PASSED ]]; then
  status="PASSED"
elif [[ "$status" != UNSUPPORTED && ( "$deployed_status" == FAILED || "$placement_status" == FAILED || \
  "$cpu_status" == FAILED || "$benchmark_status" == FAILED || "$e2e_status" == FAILED ) ]]; then
  status="FAILED"
fi

missing_json="$(printf '%s\n' "${missing_gates[@]}" | jq -Rsc 'split("\n") | map(select(length > 0))')"
jq -n \
  --arg schema_version 1 --arg generated_at_utc "$generated_at_utc" --arg profile deployed-local \
  --arg cluster "$cluster_name" --arg context "$context" --arg namespace "$namespace" \
  --arg statefulset "$statefulset_name" --arg status "$status" --arg failure_reason "$failure_reason" \
  --arg deployed_status "$deployed_status" --arg placement_status "$placement_status" \
  --arg cpu_status "$cpu_status" --arg benchmark_status "$benchmark_status" --arg e2e_status "$e2e_status" \
  --argjson runtime_evidence "$runtime_evidence" --argjson missing_gates "$missing_json" \
  '{schema_version:($schema_version|tonumber),generated_at_utc:$generated_at_utc,profile:$profile,
    cluster:$cluster,context:$context,namespace:$namespace,statefulset:$statefulset,status:$status,
    failure_reason:(if ($failure_reason | length) > 0 then $failure_reason else null end),
    gates:{deployed_fleet:$deployed_status,placement_5_5_5:$placement_status,
      pod_cpu_evidence:$cpu_status,native_capacity:$benchmark_status,e2e_metrics:$e2e_status},
    missing_gates:$missing_gates,pod_runtime:$runtime_evidence,
    claim_boundary:["local deployed evidence only","not production certification",
      "native benchmark is not Kafka end-to-end latency"]}' >"$report_path"

cat "$report_path"
printf 'report written to %s\n' "$report_path" >&2

case "$status" in
  PASSED) exit 0 ;;
  FAILED) exit 1 ;;
  *) exit 2 ;;
esac
