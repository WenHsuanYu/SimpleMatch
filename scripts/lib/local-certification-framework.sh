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

declare -gA SIMPLEMATCH_CERTIFICATION_ACTIVE_PHASE=()

_certification_phase_signature() {
  local phase="$1"
  local required_output="$2"
  shift 2
  printf '%s\0' "$source_signature" "$phase" "$required_output" "$@" |
    sha256sum | awk '{print $1}'
}

_certification_mark_phase_complete() {
  local marker_path="$1"
  local phase_signature="$2"

  mkdir -p "$(dirname -- "$marker_path")" || return 1
  printf '%s\n' "$phase_signature" >"$marker_path"
}

_certification_mark_phase_started_if_needed() {
  local started_marker

  [[ "${SIMPLEMATCH_CERTIFICATION_ACTIVE_PHASE[resumeMode]}" == FORBID ]] || \
    return 0
  started_marker="${SIMPLEMATCH_CERTIFICATION_ACTIVE_PHASE[startedMarker]}"
  mkdir -p "$(dirname -- "$started_marker")" || return 1
  printf '%s\n' "${SIMPLEMATCH_CERTIFICATION_ACTIVE_PHASE[signature]}" \
    >"$started_marker"
}

_certification_clear_phase_started_if_needed() {
  [[ "${SIMPLEMATCH_CERTIFICATION_ACTIVE_PHASE[resumeMode]}" == FORBID ]] || \
    return 0
  rm -f -- "${SIMPLEMATCH_CERTIFICATION_ACTIVE_PHASE[startedMarker]}"
}

_certification_prepare_phase_context() {
  local phase="$1"
  local required_output="$2"
  shift 2
  local marker_path started_marker phase_signature resume_decision resume_mode plan

  SIMPLEMATCH_CERTIFICATION_ACTIVE_PHASE=()
  marker_path="$phase_marker_directory/${phase}.ok"
  started_marker="$phase_marker_directory/${phase}.started"
  phase_signature="$(_certification_phase_signature \
    "$phase" "$required_output" "$@")" || return 1
  resume_mode="$(certification_phase_resume_mode "$phase")" || return 1
  SIMPLEMATCH_CERTIFICATION_ACTIVE_PHASE[phase]="$phase"
  SIMPLEMATCH_CERTIFICATION_ACTIVE_PHASE[markerPath]="$marker_path"
  SIMPLEMATCH_CERTIFICATION_ACTIVE_PHASE[startedMarker]="$started_marker"
  SIMPLEMATCH_CERTIFICATION_ACTIVE_PHASE[signature]="$phase_signature"
  SIMPLEMATCH_CERTIFICATION_ACTIVE_PHASE[requiredOutput]="$required_output"
  SIMPLEMATCH_CERTIFICATION_ACTIVE_PHASE[resumeMode]="$resume_mode"

  if [[ "$resume" == true && "$resume_mode" == FORBID && \
      -f "$started_marker" ]]; then
    printf 'same-run resume cannot determine whether non-replayable phase %s already produced side effects; start a fresh production-like run\n' \
      "$phase" >&2
    return 2
  fi

  if [[ "$resume" == true && -f "$marker_path" && \
      "$(<"$marker_path")" == "$phase_signature" ]]; then
    resume_decision="$(certification_phase_resume_decision \
      "$phase" "$required_output")" || return 1
    case "$resume_decision" in
      RESUME)
        SIMPLEMATCH_CERTIFICATION_ACTIVE_PHASE[action]=RESUME
        return 0
        ;;
      REEXECUTE)
        ;;
      REJECT)
        printf 'same-run resume cannot safely accept completed phase %s; start a fresh production-like run\n' \
          "$phase" >&2
        return 2
        ;;
      *)
        printf 'unsupported same-run resume decision for %s: %s\n' \
          "$phase" "$resume_decision" >&2
        return 1
        ;;
    esac
  fi

  check_certification_deadline
  if [[ "$dry_run" == true ]]; then
    certification_plan_phase "$phase" "$@" >/dev/null || return 1
    SIMPLEMATCH_CERTIFICATION_ACTIVE_PHASE[action]=DRY_RUN
    return 0
  fi

  SIMPLEMATCH_CERTIFICATION_ACTIVE_PHASE[startedAtUtc]="$(_certification_now_utc)"
  SIMPLEMATCH_CERTIFICATION_ACTIVE_PHASE[startedMillis]="$(_certification_now_millis)"
  plan="$(certification_plan_phase "$phase" "$@")" || return 1
  IFS='|' read -r \
    SIMPLEMATCH_CERTIFICATION_ACTIVE_PHASE[decision] \
    SIMPLEMATCH_CERTIFICATION_ACTIVE_PHASE[inputFingerprint] \
    SIMPLEMATCH_CERTIFICATION_ACTIVE_PHASE[evidenceDigest] \
    SIMPLEMATCH_CERTIFICATION_ACTIVE_PHASE[reason] <<<"$plan"
  SIMPLEMATCH_CERTIFICATION_ACTIVE_PHASE[action]=PLANNED
}

_certification_finish_phase_context() {
  local completed_millis duration_millis execution_json

  SIMPLEMATCH_CERTIFICATION_ACTIVE_PHASE[completedAtUtc]="$(_certification_now_utc)"
  completed_millis="$(_certification_now_millis)"
  duration_millis=$((
    completed_millis - SIMPLEMATCH_CERTIFICATION_ACTIVE_PHASE[startedMillis]
  ))
  SIMPLEMATCH_CERTIFICATION_ACTIVE_PHASE[durationMillis]="$duration_millis"
  execution_json="$(certification_execution_timing_json \
    "${SIMPLEMATCH_CERTIFICATION_ACTIVE_PHASE[startedAtUtc]}" \
    "${SIMPLEMATCH_CERTIFICATION_ACTIVE_PHASE[completedAtUtc]}" \
    "$duration_millis")" || return 1
  SIMPLEMATCH_CERTIFICATION_ACTIVE_PHASE[executionJson]="$execution_json"
}

_certification_record_execution_success() {
  certification_plan_record_execution \
    "${SIMPLEMATCH_CERTIFICATION_ACTIVE_PHASE[phase]}" \
    "${SIMPLEMATCH_CERTIFICATION_ACTIVE_PHASE[inputFingerprint]}" \
    "${SIMPLEMATCH_CERTIFICATION_ACTIVE_PHASE[reason]}" \
    "${SIMPLEMATCH_CERTIFICATION_ACTIVE_PHASE[executionJson]}" \
    "$@" || return 1
  _certification_mark_phase_complete \
    "${SIMPLEMATCH_CERTIFICATION_ACTIVE_PHASE[markerPath]}" \
    "${SIMPLEMATCH_CERTIFICATION_ACTIVE_PHASE[signature]}" || return 1
  _certification_clear_phase_started_if_needed
}

_certification_record_execution_failure() {
  local command_status="$1"

  certification_plan_record_failure \
    "${SIMPLEMATCH_CERTIFICATION_ACTIVE_PHASE[phase]}" \
    "${SIMPLEMATCH_CERTIFICATION_ACTIVE_PHASE[inputFingerprint]}" \
    "command exited with status $command_status" \
    "${SIMPLEMATCH_CERTIFICATION_ACTIVE_PHASE[executionJson]}" || true
}

run_logged() {
  local phase="$1"
  shift
  local log_path="$evidence_dir/logs/${phase}.log"
  local prepare_status=0 command_status decision duration_millis

  _certification_prepare_phase_context "$phase" "" "$@" || prepare_status=$?
  [[ "$prepare_status" -eq 0 ]] || return "$prepare_status"
  case "${SIMPLEMATCH_CERTIFICATION_ACTIVE_PHASE[action]}" in
    RESUME)
      completed_phases+=("$phase (same-run resume)")
      printf 'RESUME %-30s (%s)\n' \
        "$phase" "${SIMPLEMATCH_CERTIFICATION_ACTIVE_PHASE[markerPath]}"
      return 0
      ;;
    DRY_RUN)
      print_command "$@"
      return 0
      ;;
  esac

  decision="${SIMPLEMATCH_CERTIFICATION_ACTIVE_PHASE[decision]}"
  if [[ "$decision" == REUSE || "$decision" == REVALIDATE ]]; then
    _certification_finish_phase_context || return 1
    certification_plan_record_reuse \
      "$phase" "$decision" \
      "${SIMPLEMATCH_CERTIFICATION_ACTIVE_PHASE[inputFingerprint]}" \
      "${SIMPLEMATCH_CERTIFICATION_ACTIVE_PHASE[evidenceDigest]}" \
      "${SIMPLEMATCH_CERTIFICATION_ACTIVE_PHASE[reason]}" \
      "${SIMPLEMATCH_CERTIFICATION_ACTIVE_PHASE[executionJson]}" || return 1
    _certification_mark_phase_complete \
      "${SIMPLEMATCH_CERTIFICATION_ACTIVE_PHASE[markerPath]}" \
      "${SIMPLEMATCH_CERTIFICATION_ACTIVE_PHASE[signature]}" || return 1
    duration_millis="${SIMPLEMATCH_CERTIFICATION_ACTIVE_PHASE[durationMillis]}"
    completed_phases+=("$phase (${decision,,})")
    printf '%-10s %-28s (%s ms)\n' "$decision" "$phase" "$duration_millis"
    return 0
  fi
  [[ "$decision" == EXECUTE ]] || {
    printf 'unsupported certification planner decision for %s: %s\n' \
      "$phase" "$decision" >&2
    return 1
  }

  _certification_mark_phase_started_if_needed || return 1
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
  _certification_finish_phase_context || return 1

  if [[ "$command_status" -eq 0 ]]; then
    _certification_record_execution_success "$@" || {
      failed_phase="$phase"
      failure_reason="Phase passed but valid evidence could not be recorded: $phase"
      return 1
    }
    duration_millis="${SIMPLEMATCH_CERTIFICATION_ACTIVE_PHASE[durationMillis]}"
    completed_phases+=("$phase")
    printf 'PASS %-31s (%s ms, %s)\n' "$phase" "$duration_millis" "$log_path"
    return 0
  fi

  _certification_record_execution_failure "$command_status"
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
  local prepare_status=0 command_status decision duration_millis

  _certification_prepare_phase_context "$phase" "$output_path" "$@" || \
    prepare_status=$?
  [[ "$prepare_status" -eq 0 ]] || return "$prepare_status"
  case "${SIMPLEMATCH_CERTIFICATION_ACTIVE_PHASE[action]}" in
    RESUME)
      completed_phases+=("$phase (same-run resume)")
      printf 'RESUME %-30s (%s)\n' "$phase" "$output_path"
      return 0
      ;;
    DRY_RUN)
      print_command "$@"
      return 0
      ;;
  esac

  decision="${SIMPLEMATCH_CERTIFICATION_ACTIVE_PHASE[decision]}"
  [[ "$decision" == EXECUTE ]] || {
    printf 'capture phase %s is not eligible for %s without an output adapter\n' \
      "$phase" "$decision" >&2
    return 1
  }

  _certification_mark_phase_started_if_needed || return 1
  mkdir -p "$(dirname -- "$output_path")" || return 1
  set +e
  (
    set -e
    execute_with_certification_deadline "$@"
  ) >"$output_path" 2>&1
  command_status=$?
  set -e
  _certification_finish_phase_context || return 1

  if [[ "$command_status" -eq 0 ]]; then
    _certification_record_execution_success "$@" || return 1
    duration_millis="${SIMPLEMATCH_CERTIFICATION_ACTIVE_PHASE[durationMillis]}"
    completed_phases+=("$phase")
    printf 'PASS %-31s (%s ms, %s)\n' "$phase" "$duration_millis" "$output_path"
    return 0
  fi

  _certification_record_execution_failure "$command_status"
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
  elif [[ "$skip_build" == true || "$skip_compose" == true || \
      "$skip_kubernetes" == true || "$matching_fleet_only" == true ]]; then
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
      jq -r '
        .phases[] |
        "- \(.decision) \(.phaseId): \(.reason) " +
        "[lookup=\(.lookupDurationMillis)ms, " +
        "revalidation=\(.revalidationDurationMillis)ms]"
      ' "$certification_plan_file"
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
    if [[ -n "$namespace" ]] && _certification_namespace_cleanup_owned; then
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
