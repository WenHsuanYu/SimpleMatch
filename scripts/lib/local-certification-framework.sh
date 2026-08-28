#!/usr/bin/env bash

# Local production-like certification execution framework. The top-level runner
# owns shared run state; this module owns command execution, timing, current-run
# phase evidence, reporting, and cleanup.

usage() {
  cat <<'EOF'
Usage:
  scripts/run-local-production-like-certification.sh [options]

Options:
  --tag TAG               Local image tag (default: SIMPLEMATCH_LOCAL_IMAGE_TAG or local).
  --image-transport MODE  Kubernetes image transport: registry (default) or kind-load fallback.
  --skip-build            Reuse local images without proving the image-build requirement.
  --skip-compose          Skip PostgreSQL, Redis, Kafka, and Kafka Connect runtime checks.
  --skip-kubernetes       Skip the live Kubernetes deployment and Matching fleet checks.
  --matching-fleet-only    Run a clean local Kafka plus Matching fleet gate; skip Flyway and other
                           runtime workloads. The report is intentionally PARTIAL.
  --keep-resources         Keep only this run's Compose project and Kubernetes namespace.
  --resume                 Continue the same evidence directory and retained namespace.
  --dry-run                Print planned commands without changing external state.
  --help                   Show this help.

Verified cross-run reuse is automatic and independent of --resume. Reusable
phase evidence must match exact effective inputs and immutable output identity.
Runtime-state-dependent phases still execute fresh for every new full run.
EOF
}

die() {
  failure_reason="$*"
  printf '%s\n' "$*" >&2
  exit 1
}

print_command() {
  printf 'DRY RUN:'
  printf ' %q' "$@"
  printf '\n'
}

_certification_now_utc() {
  date -u +%Y-%m-%dT%H:%M:%S.%3NZ
}

_certification_now_millis() {
  date +%s%3N
}

_certification_resume_marker_valid() {
  local phase="$1"
  local marker_path="$2"
  local phase_signature="$3"
  local required_output="${4:-}"

  [[ "$resume" == true && -f "$marker_path" ]] || return 1
  [[ "$(<"$marker_path")" == "$phase_signature" ]] || return 1
  certification_phase_resume_result_valid "$phase" || return 1
  [[ -z "$required_output" || -f "$required_output" ]]
}

_certification_mark_phase_complete() {
  local marker_path="$1"
  local phase_signature="$2"

  mkdir -p "$(dirname -- "$marker_path")" || return 1
  printf '%s\n' "$phase_signature" >"$marker_path"
}

run_logged() {
  local phase="$1"
  shift
  local log_path="$evidence_dir/logs/${phase}.log"
  local marker_path="$phase_marker_directory/${phase}.ok"
  local phase_signature plan decision input_fingerprint evidence_digest reason
  local started_at_utc completed_at_utc started_millis completed_millis duration_millis
  local command_status

  phase_signature="$(printf '%s\0' "$source_signature" "$phase" "$@" | sha256sum | awk '{print $1}')"
  if _certification_resume_marker_valid "$phase" "$marker_path" "$phase_signature"; then
    completed_phases+=("$phase (same-run resume)")
    printf 'RESUME %-30s (%s)\n' "$phase" "$marker_path"
    return 0
  fi

  check_certification_deadline
  if [[ "$dry_run" == true ]]; then
    certification_plan_phase "$phase" "$@" >/dev/null || return 1
    print_command "$@"
    return 0
  fi

  started_at_utc="$(_certification_now_utc)"
  started_millis="$(_certification_now_millis)"
  plan="$(certification_plan_phase "$phase" "$@")" || return 1
  IFS='|' read -r decision input_fingerprint evidence_digest reason <<<"$plan"

  if [[ "$decision" == REUSE || "$decision" == REVALIDATE ]]; then
    completed_at_utc="$(_certification_now_utc)"
    completed_millis="$(_certification_now_millis)"
    duration_millis=$((completed_millis - started_millis))
    certification_plan_record_reuse \
      "$phase" "$decision" "$input_fingerprint" "$evidence_digest" "$reason" \
      "$started_at_utc" "$completed_at_utc" "$duration_millis" || return 1
    _certification_mark_phase_complete "$marker_path" "$phase_signature" || return 1
    completed_phases+=("$phase (${decision,,})")
    printf '%-10s %-28s (%s ms)\n' "$decision" "$phase" "$duration_millis"
    return 0
  fi
  [[ "$decision" == EXECUTE ]] || {
    printf 'unsupported certification planner decision for %s: %s\n' \
      "$phase" "$decision" >&2
    return 1
  }

  mkdir -p "$(dirname -- "$log_path")" || return 1
  printf '$' >"$log_path" || return 1
  printf ' %q' "$@" >>"$log_path" || return 1
  printf '\n' >>"$log_path" || return 1
  set +e
  (
    set -e
    execute_with_certification_deadline "$@"
  ) >>"$log_path" 2>&1
  command_status=$?
  set -e

  completed_at_utc="$(_certification_now_utc)"
  completed_millis="$(_certification_now_millis)"
  duration_millis=$((completed_millis - started_millis))
  if [[ "$command_status" -eq 0 ]]; then
    certification_plan_record_execution \
      "$phase" "$input_fingerprint" "$reason" \
      "$started_at_utc" "$completed_at_utc" "$duration_millis" "$@" || {
      failed_phase="$phase"
      failure_reason="Phase passed but valid evidence could not be recorded: $phase"
      return 1
    }
    _certification_mark_phase_complete "$marker_path" "$phase_signature" || return 1
    completed_phases+=("$phase")
    printf 'PASS %-31s (%s ms, %s)\n' "$phase" "$duration_millis" "$log_path"
    return 0
  fi

  certification_plan_record_failure \
    "$phase" "$input_fingerprint" "command exited with status $command_status" \
    "$started_at_utc" "$completed_at_utc" "$duration_millis" || true
  failed_phase="$phase"
  failure_reason="Phase failed: $phase"
  cat "$log_path" >&2
  return "$command_status"
}

run_refreshable_logged() {
  local phase="$1"
  if [[ "$dry_run" == false ]]; then
    rm -f "$phase_marker_directory/${phase}.ok"
  fi
  run_logged "$@"
}

run_capture() {
  local phase="$1"
  local output_path="$2"
  shift 2
  local marker_path="$phase_marker_directory/${phase}.ok"
  local phase_signature plan decision input_fingerprint evidence_digest reason
  local started_at_utc completed_at_utc started_millis completed_millis duration_millis
  local command_status

  phase_signature="$(printf '%s\0' "$source_signature" "$phase" "$output_path" "$@" | sha256sum | awk '{print $1}')"
  if _certification_resume_marker_valid \
      "$phase" "$marker_path" "$phase_signature" "$output_path"; then
    completed_phases+=("$phase (same-run resume)")
    printf 'RESUME %-30s (%s)\n' "$phase" "$output_path"
    return 0
  fi

  check_certification_deadline
  if [[ "$dry_run" == true ]]; then
    certification_plan_phase "$phase" "$@" >/dev/null || return 1
    print_command "$@"
    return 0
  fi

  started_at_utc="$(_certification_now_utc)"
  started_millis="$(_certification_now_millis)"
  plan="$(certification_plan_phase "$phase" "$@")" || return 1
  IFS='|' read -r decision input_fingerprint evidence_digest reason <<<"$plan"
  [[ "$decision" == EXECUTE ]] || {
    printf 'capture phase %s is not eligible for %s without an output adapter\n' \
      "$phase" "$decision" >&2
    return 1
  }

  mkdir -p "$(dirname -- "$output_path")" || return 1
  set +e
  (
    set -e
    execute_with_certification_deadline "$@"
  ) >"$output_path" 2>&1
  command_status=$?
  set -e
  completed_at_utc="$(_certification_now_utc)"
  completed_millis="$(_certification_now_millis)"
  duration_millis=$((completed_millis - started_millis))

  if [[ "$command_status" -eq 0 ]]; then
    certification_plan_record_execution \
      "$phase" "$input_fingerprint" "$reason" \
      "$started_at_utc" "$completed_at_utc" "$duration_millis" "$@" || return 1
    _certification_mark_phase_complete "$marker_path" "$phase_signature" || return 1
    completed_phases+=("$phase")
    printf 'PASS %-31s (%s ms, %s)\n' "$phase" "$duration_millis" "$output_path"
    return 0
  fi

  certification_plan_record_failure \
    "$phase" "$input_fingerprint" "command exited with status $command_status" \
    "$started_at_utc" "$completed_at_utc" "$duration_millis" || true
  failed_phase="$phase"
  failure_reason="Phase failed: $phase"
  cat "$output_path" >&2
  return "$command_status"
}

check_certification_deadline() {
  local now remaining
  if [[ "$certification_deadline_epoch" -eq 0 ]]; then
    return 0
  fi
  now="$(date +%s)"
  remaining=$((certification_deadline_epoch - now))
  if (( remaining <= 0 )); then
    die "Certification exceeded SIMPLEMATCH_CERTIFICATION_TIMEOUT_SECONDS=${certification_timeout_seconds}."
  fi
}

certification_deadline_remaining() {
  local now remaining
  if [[ "$certification_deadline_epoch" -eq 0 ]]; then
    printf '%s\n' 0
    return 0
  fi
  now="$(date +%s)"
  remaining=$((certification_deadline_epoch - now))
  (( remaining > 0 )) || return 1
  printf '%s\n' "$remaining"
}

execute_with_certification_deadline() {
  local remaining
  check_certification_deadline
  if declare -F "$1" >/dev/null 2>&1; then
    "$@"
    return
  fi
  remaining="$(certification_deadline_remaining)" || die \
    "Certification deadline expired while starting $1."
  timeout --foreground "${remaining}s" "$@"
}

write_report() {
  local exit_code="$1"
  [[ "$dry_run" == true ]] && return 0

  mkdir -p "$evidence_dir"
  if [[ "$exit_code" -ne 0 ]]; then
    completion_status="FAILED"
  elif [[ "$skip_build" == true || "$skip_compose" == true || "$skip_kubernetes" == true || "$matching_fleet_only" == true ]]; then
    completion_status="PARTIAL"
    failure_reason="One or more certification phases were explicitly skipped."
  else
    completion_status="PASSED"
  fi

  {
    printf '%s\n\n' '# SimpleMatch local production-like certification'
    printf '%s\n' "- status: $completion_status"
    printf '%s\n' "- generated_at_utc: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
    printf '%s\n' "- local_image_tag: $image_tag"
    printf '%s\n' "- image_transport: $image_transport"
    if [[ "$image_transport" == registry ]]; then
      printf '%s\n' "- image_lock: ${image_lock#$repo_root/}"
    fi
    printf '%s\n' "- compose_project: $compose_project"
    printf '%s\n' "- compose_file: ${compose_file#$repo_root/}"
    printf '%s\n' "- kubernetes_namespace: ${namespace:-not-run}"
    printf '%s\n' "- trading_day: $certification_trading_day"
    if [[ -n "$failed_phase" ]]; then
      printf '%s\n' "- failed_phase: $failed_phase"
    fi
    if [[ -n "$failure_reason" ]]; then
      printf '%s\n' "- note: $failure_reason"
    fi
    printf '\n%s\n\n' '## Completed phases'
    if [[ ${#completed_phases[@]} -eq 0 ]]; then
      printf '%s\n' '- none'
    else
      printf '%s\n' "${completed_phases[@]}" | sed 's/^/- /'
    fi
    if [[ -f "$certification_plan_file" ]]; then
      printf '\n%s\n\n' '## Phase plan'
      jq -r '.phases[] | "- \(.decision) \(.phaseId): \(.reason)"' \
        "$certification_plan_file"
    fi
  } >"$evidence_dir/report.md"
}

cleanup() {
  local exit_code="$?"
  local namespace_cleanup_failed=false

  if [[ "$dry_run" == false && "$keep_resources" == false ]]; then
    if [[ "$compose_started" == true ]]; then
      "${compose_command[@]}" down --volumes --remove-orphans >/dev/null 2>&1 || true
    fi
    if [[ "$kubernetes_namespace_created" == true && -n "$namespace" ]]; then
      if ! simplematch_kind_delete_disposable_namespace \
          "$kind_context" "$namespace" "$namespace_cleanup_timeout" >/dev/null 2>&1; then
        namespace_cleanup_failed=true
        printf 'Disposable namespace cleanup did not complete: %s\n' "$namespace" >&2
      fi
    fi
  fi

  if [[ "$namespace_cleanup_failed" == true && "$exit_code" -eq 0 ]]; then
    exit_code=1
    failed_phase="cleanup"
    failure_reason="Disposable Kubernetes namespace cleanup did not complete."
  fi

  write_report "$exit_code"
  trap - EXIT
  exit "$exit_code"
}
trap cleanup EXIT
