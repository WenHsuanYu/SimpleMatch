#!/usr/bin/env bash

# Local production-like certification execution framework.
# Sourced by run-local-production-like-certification.sh; shared run state is owned
# by the top-level orchestrator. This file defines behavior only and has no entry point.

usage() {
  cat <<'EOF'
Usage:
  scripts/run-local-production-like-certification.sh [options]

Options:
  --tag TAG               Local image tag (default: SIMPLEMATCH_LOCAL_IMAGE_TAG or local).
  --image-transport MODE  Kubernetes image transport: registry (default) or kind-load fallback.
  --skip-build            Reuse local images instead of running the image build workflow.
  --skip-compose          Skip PostgreSQL, Redis, Kafka, and Kafka Connect runtime checks.
  --skip-kubernetes        Skip the live Kubernetes deployment and Matching fleet checks.
  --matching-fleet-only    Run a clean local Kafka plus Matching fleet gate; skip Flyway and other
                           runtime workloads. The report is intentionally PARTIAL.
  --keep-resources         Keep only this run's Compose project and Kubernetes namespace.
  --resume                 Reuse successful phases from the supplied evidence directory and
                           namespace; requires SIMPLEMATCH_CERTIFICATION_EVIDENCE_DIR and
                           SIMPLEMATCH_CERTIFICATION_NAMESPACE.
  --dry-run                Print planned commands without changing external state.
  --help                   Show this help.

The local gate owns only the Compose project named by
SIMPLEMATCH_CERTIFICATION_COMPOSE_PROJECT and a generated Kubernetes namespace.
Generated namespaces are labeled simplematch.io/lifecycle=disposable; that label
is the authoritative routine-cleanup boundary. registry is the default image
transport and publishes only to the configured local registry, rendering runtime
images by immutable digest. kind-load remains an explicit compatibility fallback
that imports the local image inventory into kind without using registry digest
substitution. Neither path publishes to staging, production, or a remote registry.
The Kubernetes gate uses the approved delivery manifest for
SIMPLEMATCH_CERTIFICATION_TRADING_DAY (default: current UTC day) under
tools/market-reference-builder/data, or the path supplied by
SIMPLEMATCH_MARKET_REFERENCE_DELIVERY_MANIFEST.
The Kafka profile gate generates source-backed producer evidence and measures free Docker
filesystem capacity for the local brokers. The default workload envelope is the bounded local
side-project scenario under scripts/testdata/matching-topic-profile/local/capacity.properties;
override it with SIMPLEMATCH_KAFKA_CAPACITY_WORKLOAD_FILE when a different explicitly documented
workload envelope is required. Override producer or capacity evidence with
SIMPLEMATCH_KAFKA_PRODUCER_CONFIG_FILE or SIMPLEMATCH_KAFKA_CAPACITY_EVIDENCE_FILE.
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

run_logged() {
  local phase="$1"
  shift
  local log_path="$evidence_dir/${phase}.log"
  local marker_path="$phase_marker_directory/${phase}.ok"
  local phase_signature

  phase_signature="$(printf '%s\0' "$source_signature" "$phase" "$@" | sha256sum | awk '{print $1}')"

  if [[ "$resume" == true && -f "$marker_path" && "$(<"$marker_path")" == "$phase_signature" ]]; then
    completed_phases+=("$phase (reused)")
    printf 'REUSE %-32s (%s)\n' "$phase" "$marker_path"
    return 0
  fi

  check_certification_deadline

  if [[ "$dry_run" == true ]]; then
    print_command "$@"
    return 0
  fi

  mkdir -p "$evidence_dir"
  printf '$' >"$log_path"
  printf ' %q' "$@" >>"$log_path"
  printf '\n' >>"$log_path"
  local command_status
  set +e
  (
    set -e
    execute_with_certification_deadline "$@"
  ) >>"$log_path" 2>&1
  command_status=$?
  set -e
  if [[ "$command_status" -eq 0 ]]; then
    mkdir -p "$phase_marker_directory"
    printf '%s\n' "$phase_signature" >"$marker_path"
    completed_phases+=("$phase")
    printf 'PASS %-32s (%s)\n' "$phase" "$log_path"
    return 0
  fi

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
  local phase_signature

  phase_signature="$(printf '%s\0' "$source_signature" "$phase" "$output_path" "$@" | sha256sum | awk '{print $1}')"

  if [[ "$resume" == true && -f "$marker_path" && "$(<"$marker_path")" == "$phase_signature" ]]; then
    completed_phases+=("$phase (reused)")
    printf 'REUSE %-32s (%s)\n' "$phase" "$output_path"
    return 0
  fi

  check_certification_deadline

  if [[ "$dry_run" == true ]]; then
    print_command "$@"
    return 0
  fi

  mkdir -p "$(dirname -- "$output_path")" "$evidence_dir"
  local command_status
  set +e
  (
    set -e
    execute_with_certification_deadline "$@"
  ) >"$output_path" 2>&1
  command_status=$?
  set -e
  if [[ "$command_status" -eq 0 ]]; then
    mkdir -p "$phase_marker_directory"
    printf '%s\n' "$phase_signature" >"$marker_path"
    completed_phases+=("$phase")
    printf 'PASS %-32s (%s)\n' "$phase" "$output_path"
    return 0
  fi

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
    printf '%s\n' "- gradle: 9.7.0"
    printf '%s\n' "- spring_boot: 4.1.0"
    printf '%s\n' "- vcpkg: 2026.07.29"
    printf '%s\n' "- matching_base: ubuntu:26.04"
    printf '%s\n' "- kafka: 4.3.1"
    printf '%s\n' "- postgresql: 18.4"
    printf '%s\n' "- redis: 8.8.1-alpine"
    printf '%s\n' "- debezium: 3.6.0.Final"
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
