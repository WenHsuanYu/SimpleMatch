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
  --query-fixture-directory PRIVATE_PATH
                         Pre-provisioned Query/FIX synthetic bundle; required for full Kubernetes.
  --skip-build            Reuse local images without proving the image-build requirement.
  --skip-compose          Skip PostgreSQL, Redis, Kafka, and Kafka Connect runtime checks.
  --skip-kubernetes       Skip the live Kubernetes deployment and Matching fleet checks.
  --matching-fleet-only    Run a clean local Kafka plus Matching fleet gate; skip Flyway and other
                           runtime workloads. The report is intentionally PARTIAL.
  --keep-resources         Keep only this run's Compose project and Kubernetes namespace.
  --dry-run                Print planned commands without changing external state.
  --help                   Show this help.

Every selected phase executes in a new evidence directory. Build tools retain
their normal caches. An interrupted certification starts a new run.
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

_certification_run_command() {
  local phase="$1" output="$2" capture="$3"
  shift 3
  local command_status

  check_certification_deadline
  if [[ "$dry_run" == true ]]; then
    printf 'PHASE %s\n' "$phase"
    print_command "$@"
    return 0
  fi
  certification_write_phase_result "$phase" RUNNING || return 1
  mkdir -p "$(dirname -- "$output")" || return 1
  if [[ "$capture" == false ]]; then
    printf '$' >"$output" || return 1
    printf ' %q' "$@" >>"$output" || return 1
    printf '\n' >>"$output" || return 1
  else
    : >"$output" || return 1
  fi
  set +e
  (set -e; execute_with_certification_deadline "$@") >>"$output" 2>&1
  command_status=$?
  set -e
  if [[ "$command_status" -eq 0 ]]; then
    certification_write_phase_result "$phase" PASS 0 || return 1
    completed_phases+=("$phase")
    printf 'PASS %-31s (%s)\n' "$phase" "$output"
  else
    certification_write_phase_result "$phase" FAIL "$command_status" || return 1
    failed_phase="$phase"
    failure_reason="Phase failed: $phase"
    cat "$output" >&2
    return "$command_status"
  fi
}

run_logged() {
  local phase="$1"
  shift
  _certification_run_command "$phase" "$evidence_dir/logs/$phase.log" false "$@"
}

run_capture() {
  local phase="$1" output="$2"
  shift 2
  _certification_run_command "$phase" "$output" true "$@"
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
  local completed_phase budget_phase_completed=false
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
  for completed_phase in "${completed_phases[@]}"; do
    case "$completed_phase" in
      local-resource-budget)
        budget_phase_completed=true
        ;;
    esac
  done

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
    if [[ "$budget_phase_completed" == true && -f "$evidence_dir/local-resource-budget.json" ]]; then
      printf '%s\n' "- declared_resource_budget: $(jq -r \
        'if .requests_within_host_budget then "within selected host capacity" else "exceeds selected host capacity; budget check did not block runtime" end' \
        "$evidence_dir/local-resource-budget.json")"
      printf '%s\n' '- resource_budget_evidence: local-resource-budget.json'
    fi
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
