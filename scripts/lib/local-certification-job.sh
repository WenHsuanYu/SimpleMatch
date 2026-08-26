#!/usr/bin/env bash

# Kubernetes Job supervision for local certification.
# Sourced by run-local-production-like-certification.sh; this module observes
# terminal Job conditions and preserves evidence without knowing Job semantics.

collect_kubernetes_job_evidence() {
  local job_name="$1"
  local output_dir="$2"
  local pod

  mkdir -p "$output_dir"
  kubectl --context "$kind_context" -n "$namespace" get job "$job_name" -o yaml \
    >"$output_dir/job.yaml" 2>"$output_dir/job-get.stderr" || true
  kubectl --context "$kind_context" -n "$namespace" describe job "$job_name" \
    >"$output_dir/job.describe.txt" 2>"$output_dir/job-describe.stderr" || true
  kubectl --context "$kind_context" -n "$namespace" get pods \
    -l "job-name=$job_name" -o wide \
    >"$output_dir/pods.txt" 2>"$output_dir/pods-get.stderr" || true
  kubectl --context "$kind_context" -n "$namespace" get events \
    --sort-by=.lastTimestamp \
    >"$output_dir/events.txt" 2>"$output_dir/events-get.stderr" || true

  while IFS= read -r pod; do
    [[ -n "$pod" ]] || continue
    kubectl --context "$kind_context" -n "$namespace" describe pod "$pod" \
      >"$output_dir/${pod}.describe.txt" 2>"$output_dir/${pod}.describe.stderr" || true
    kubectl --context "$kind_context" -n "$namespace" logs "$pod" \
      --all-containers=true --prefix=true \
      >"$output_dir/${pod}.log" 2>"$output_dir/${pod}.log.stderr" || true
  done < <(kubectl --context "$kind_context" -n "$namespace" get pods \
    -l "job-name=$job_name" -o jsonpath='{range .items[*]}{.metadata.name}{"\n"}{end}' \
    2>/dev/null || true)
}

kubernetes_job_conditions() {
  local job_name="$1"
  kubectl --context "$kind_context" -n "$namespace" get job "$job_name" \
    -o jsonpath='{range .status.conditions[*]}{.type}={.status}:{.reason}{"\n"}{end}'
}

supervise_kubernetes_job() {
  local job_name="$1"
  local supervisor_timeout_seconds="$2"
  local output_dir="$3"
  local started_at now last_snapshot_at conditions snapshot_dir

  [[ "$supervisor_timeout_seconds" =~ ^[1-9][0-9]*$ ]] || {
    printf 'Invalid supervisor timeout for Job %s: %s\n' \
      "$job_name" "$supervisor_timeout_seconds" >&2
    return 2
  }
  [[ "$kubernetes_job_evidence_interval_seconds" =~ ^[1-9][0-9]*$ ]] || {
    printf 'Invalid Kubernetes Job evidence interval: %s\n' \
      "$kubernetes_job_evidence_interval_seconds" >&2
    return 2
  }

  mkdir -p "$output_dir"
  started_at="$(date +%s)"
  last_snapshot_at=0

  while true; do
    check_certification_deadline
    now="$(date +%s)"
    conditions="$(kubernetes_job_conditions "$job_name" 2>/dev/null || true)"

    if grep -Eq '^Complete=True:' <<<"$conditions"; then
      collect_kubernetes_job_evidence "$job_name" "$output_dir/final"
      return 0
    fi
    if grep -Eq '^(Failed|FailureTarget)=True:' <<<"$conditions"; then
      printf 'Kubernetes Job %s failed: %s\n' \
        "$job_name" "${conditions//$'\n'/, }" >&2
      collect_kubernetes_job_evidence "$job_name" "$output_dir/final"
      return 1
    fi

    if (( now - started_at >= supervisor_timeout_seconds )); then
      printf 'Kubernetes Job %s exceeded supervisor deadline of %ss.\n' \
        "$job_name" "$supervisor_timeout_seconds" >&2
      collect_kubernetes_job_evidence "$job_name" "$output_dir/final"
      return 124
    fi

    if (( last_snapshot_at == 0 || now - last_snapshot_at >= kubernetes_job_evidence_interval_seconds )); then
      snapshot_dir="$output_dir/snapshots/$(date -u +%Y%m%dT%H%M%SZ)"
      collect_kubernetes_job_evidence "$job_name" "$snapshot_dir"
      last_snapshot_at="$now"
    fi
    sleep 1
  done
}
