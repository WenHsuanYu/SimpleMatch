#!/usr/bin/env bash

# Phase definitions for local production-like certification. This module owns
# dependency, active-profile selection, reuse/resume policy, declared input and
# output kinds, and topological ordering. It does not execute phases or inspect
# reusable evidence.

_phase_graph_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/lib/local-image-inventory.sh
source "$_phase_graph_dir/local-image-inventory.sh"
unset _phase_graph_dir

declare -gA SIMPLEMATCH_CERTIFICATION_PHASE_POLICY=()
declare -gA SIMPLEMATCH_CERTIFICATION_PHASE_DEPENDENCIES=()
declare -gA SIMPLEMATCH_CERTIFICATION_PHASE_VERSION=()
declare -gA SIMPLEMATCH_CERTIFICATION_PHASE_INPUT_KINDS=()
declare -gA SIMPLEMATCH_CERTIFICATION_PHASE_OUTPUT_KINDS=()
declare -gA SIMPLEMATCH_CERTIFICATION_PHASE_RESUME_MODE=()
declare -ga SIMPLEMATCH_CERTIFICATION_PHASE_ORDER=()
SIMPLEMATCH_CERTIFICATION_PHASE_GRAPH_INITIALIZED=false

_certification_phase_id_is_valid() {
  local phase_id="${1:-}"
  [[ -n "$phase_id" \
    && "$phase_id" =~ ^[a-z0-9]([a-z0-9/-]*[a-z0-9])?$ \
    && "$phase_id" != *'..'* \
    && "$phase_id" != *'//'* ]]
}

_certification_require_phase_id() {
  local phase_id="${1:-}"
  _certification_phase_id_is_valid "$phase_id" || {
    printf 'invalid certification phase id: %s\n' "${phase_id:-<empty>}" >&2
    return 1
  }
}

_certification_register_phase() {
  local phase_id="$1"
  local policy="$2"
  local resume_mode="$3"
  local input_kinds="$4"
  local output_kinds="$5"
  local dependencies="${6:-}"
  local definition_version="${7:-1}"

  _certification_require_phase_id "$phase_id" || return 1
  case "$policy" in
    FRESH|CONTENT_ADDRESSED|REVALIDATE) ;;
    *)
      printf 'invalid certification phase policy for %s: %s\n' \
        "$phase_id" "$policy" >&2
      return 1
      ;;
  esac
  case "$resume_mode" in
    REEXECUTE|REUSE_RESULT|VALIDATE|FORBID) ;;
    *)
      printf 'invalid certification resume mode for %s: %s\n' \
        "$phase_id" "$resume_mode" >&2
      return 1
      ;;
  esac
  [[ -n "$input_kinds" && -n "$output_kinds" ]] || {
    printf 'certification phase %s must declare input and output kinds\n' \
      "$phase_id" >&2
    return 1
  }
  [[ "$definition_version" =~ ^[1-9][0-9]*$ ]] || {
    printf 'invalid certification phase definition version for %s: %s\n' \
      "$phase_id" "$definition_version" >&2
    return 1
  }
  [[ -z "${SIMPLEMATCH_CERTIFICATION_PHASE_POLICY[$phase_id]+x}" ]] || {
    printf 'duplicate certification phase id: %s\n' "$phase_id" >&2
    return 1
  }

  SIMPLEMATCH_CERTIFICATION_PHASE_POLICY["$phase_id"]="$policy"
  SIMPLEMATCH_CERTIFICATION_PHASE_RESUME_MODE["$phase_id"]="$resume_mode"
  SIMPLEMATCH_CERTIFICATION_PHASE_INPUT_KINDS["$phase_id"]="$input_kinds"
  SIMPLEMATCH_CERTIFICATION_PHASE_OUTPUT_KINDS["$phase_id"]="$output_kinds"
  SIMPLEMATCH_CERTIFICATION_PHASE_DEPENDENCIES["$phase_id"]="$dependencies"
  SIMPLEMATCH_CERTIFICATION_PHASE_VERSION["$phase_id"]="$definition_version"
  SIMPLEMATCH_CERTIFICATION_PHASE_ORDER+=("$phase_id")
}

_certification_register_fixed_phases() {
  local phase_id policy resume_mode input_kinds output_kinds dependencies version

  while IFS='|' read -r \
      phase_id policy resume_mode input_kinds output_kinds dependencies version; do
    [[ -n "$phase_id" ]] || continue
    _certification_register_phase \
      "$phase_id" "$policy" "$resume_mode" "$input_kinds" "$output_kinds" \
      "$dependencies" "${version:-1}" || return 1
  done <<'EOF_PHASES'
source-preflight|FRESH|REEXECUTE|source|source-revision||1
static-kubernetes-overlays|CONTENT_ADDRESSED|REUSE_RESULT|source configuration|validation-result|source-preflight|1
static-phase1-deployment-contracts|CONTENT_ADDRESSED|REUSE_RESULT|source configuration|validation-result|source-preflight|1
static-kubernetes-dependencies|CONTENT_ADDRESSED|REUSE_RESULT|source configuration|validation-result|source-preflight|1
static-matching-manifests|CONTENT_ADDRESSED|REUSE_RESULT|source configuration|validation-result|source-preflight|1
static-matching-profile|CONTENT_ADDRESSED|REUSE_RESULT|source configuration|validation-result|source-preflight|1
static-flyway-services|CONTENT_ADDRESSED|REUSE_RESULT|source configuration|validation-result|source-preflight|1
compose-config|CONTENT_ADDRESSED|REUSE_RESULT|source configuration|validation-result|source-preflight|1
cdc-outbox-failure-live|FRESH|REEXECUTE|runtime-state configuration|fault-proof|static-phase1-deployment-contracts compose-config|1
local-image-inventory|CONTENT_ADDRESSED|REUSE_RESULT|source configuration|image-inventory|source-preflight|1
kafka-producer-contract|CONTENT_ADDRESSED|REUSE_RESULT|source configuration|producer-config|static-matching-profile|1
compose-up|FRESH|REEXECUTE|configuration runtime|runtime-state|compose-config cdc-outbox-failure-live|1
compose-wait|FRESH|REEXECUTE|runtime-state|runtime-proof|compose-up|1
compose-status|FRESH|REEXECUTE|runtime-state|runtime-proof|compose-wait|1
kafka-capacity-evidence|FRESH|REEXECUTE|runtime-state workload|capacity-evidence|compose-wait|1
kafka-create-matching-commands|FRESH|REEXECUTE|runtime-state configuration|runtime-state|compose-wait|1
kafka-create-matching-events|FRESH|REEXECUTE|runtime-state configuration|runtime-state|compose-wait|1
kafka-create-account-lifecycle|FRESH|REEXECUTE|runtime-state configuration|runtime-state|compose-wait|1
kafka-create-marketdata-events|FRESH|REEXECUTE|runtime-state configuration|runtime-state|compose-wait|1
kafka-describe-matching-commands|FRESH|REEXECUTE|runtime-state|captured-output|kafka-create-matching-commands|1
kafka-config-matching-commands|FRESH|REEXECUTE|runtime-state|captured-output|kafka-create-matching-commands|1
kafka-describe-matching-events|FRESH|REEXECUTE|runtime-state|captured-output|kafka-create-matching-events|1
kafka-config-matching-events|FRESH|REEXECUTE|runtime-state|captured-output|kafka-create-matching-events|1
kafka-broker-config|FRESH|REEXECUTE|runtime-state|captured-output|compose-wait|1
kafka-profile-validation|FRESH|REEXECUTE|runtime-state captured-output configuration|runtime-proof||1
kafka-broker-failure-live|FRESH|REEXECUTE|runtime-state configuration|fault-proof|kafka-profile-validation|1
compose-down-before-kubernetes|FRESH|REEXECUTE|runtime-state|runtime-transition|kafka-broker-failure-live|1
registry-connectivity|FRESH|REEXECUTE|registry runtime-state|runtime-proof||1
registry-image-lock|CONTENT_ADDRESSED|REUSE_RESULT|registry-image configuration|image-lock||1
kind-load-import|FRESH|REEXECUTE|docker-image runtime-state|runtime-proof||1
kubernetes-manifest-split|FRESH|REEXECUTE|source configuration image-lock|manifest| |1
kubernetes-namespace|FRESH|VALIDATE|runtime-state configuration|namespace| |1
kubernetes-inputs|FRESH|REEXECUTE|configuration artifact namespace|runtime-state|kubernetes-namespace|1
kubernetes-platform-apply|FRESH|REEXECUTE|manifest namespace|runtime-state|kubernetes-inputs|1
kubernetes-migrations|FRESH|REEXECUTE|manifest runtime-state|migration-proof|kubernetes-platform-apply static-flyway-services|1
kubernetes-matching-manifest|FRESH|REEXECUTE|manifest runtime-state|manifest|kubernetes-platform-apply|1
kubernetes-topic-provisioning|FRESH|REEXECUTE|manifest runtime-state|runtime-proof|kubernetes-platform-apply|1
kubernetes-open-barriers|FRESH|FORBID|artifact image runtime-state|runtime-proof||1
kubernetes-workload-apply|FRESH|REEXECUTE|manifest runtime-state|runtime-state|kubernetes-open-barriers|1
kubernetes-matching-apply|FRESH|REEXECUTE|manifest runtime-state|runtime-state|kubernetes-open-barriers|1
kubernetes-risk-outbox-connector|FRESH|REEXECUTE|runtime-state configuration|runtime-state|kubernetes-workload-apply|1
kubernetes-account-outbox-connector|FRESH|REEXECUTE|runtime-state configuration|runtime-state|kubernetes-workload-apply|1
kubernetes-marketdata-outbox-connector|FRESH|REEXECUTE|runtime-state configuration|runtime-state|kubernetes-workload-apply|1
kubernetes-workloads|FRESH|REEXECUTE|runtime-state|runtime-proof|kubernetes-risk-outbox-connector kubernetes-account-outbox-connector kubernetes-marketdata-outbox-connector|1
kubernetes-cdc-delivery|FRESH|REEXECUTE|runtime-state configuration|runtime-proof|kubernetes-workloads|1
kubernetes-matching-workloads|FRESH|REEXECUTE|runtime-state|runtime-proof|kubernetes-matching-apply|1
kubernetes-fleet|FRESH|REEXECUTE|runtime-state|runtime-proof||1
retained-run-provenance|FRESH|REEXECUTE|runtime-proof source image-lock|provenance|kubernetes-fleet|1
EOF_PHASES
}

certification_phase_graph_initialize() {
  local service inventory_entries

  [[ "$SIMPLEMATCH_CERTIFICATION_PHASE_GRAPH_INITIALIZED" == true ]] && return 0
  _certification_register_fixed_phases || return 1
  inventory_entries="$(simplematch_local_image_inventory_entries)" || return 1
  while IFS='|' read -r _ service _ _; do
    [[ -n "$service" ]] || continue
    _certification_register_phase \
      "local-image-build/$service" CONTENT_ADDRESSED REUSE_RESULT \
      'source configuration toolchain' docker-image local-image-inventory 1 || return 1
    _certification_register_phase \
      "registry-publish/$service" REVALIDATE REEXECUTE \
      'docker-image registry configuration' registry-image '' 1 || return 1
  done <<<"$inventory_entries"
  SIMPLEMATCH_CERTIFICATION_PHASE_GRAPH_INITIALIZED=true
}

_certification_require_known_phase() {
  local phase_id="${1:-}"

  _certification_require_phase_id "$phase_id" || return 1
  certification_phase_graph_initialize || return 1
  [[ -n "${SIMPLEMATCH_CERTIFICATION_PHASE_POLICY[$phase_id]+x}" ]] || {
    printf 'unknown certification phase: %s\n' "$phase_id" >&2
    return 1
  }
}

certification_phase_ids() {
  certification_phase_graph_initialize || return 1
  printf '%s\n' "${SIMPLEMATCH_CERTIFICATION_PHASE_ORDER[@]}"
}

certification_phase_policy() {
  local phase_id="${1:-}"
  _certification_require_known_phase "$phase_id" || return 1
  printf '%s\n' "${SIMPLEMATCH_CERTIFICATION_PHASE_POLICY[$phase_id]}"
}

certification_phase_definition_version() {
  local phase_id="${1:-}"
  _certification_require_known_phase "$phase_id" || return 1
  printf '%s\n' "${SIMPLEMATCH_CERTIFICATION_PHASE_VERSION[$phase_id]}"
}

certification_phase_input_kinds() {
  local phase_id="${1:-}"
  _certification_require_known_phase "$phase_id" || return 1
  printf '%s\n' "${SIMPLEMATCH_CERTIFICATION_PHASE_INPUT_KINDS[$phase_id]}"
}

certification_phase_output_kinds() {
  local phase_id="${1:-}"
  _certification_require_known_phase "$phase_id" || return 1
  printf '%s\n' "${SIMPLEMATCH_CERTIFICATION_PHASE_OUTPUT_KINDS[$phase_id]}"
}

certification_phase_resume_mode() {
  local phase_id="${1:-}"
  _certification_require_known_phase "$phase_id" || return 1
  printf '%s\n' "${SIMPLEMATCH_CERTIFICATION_PHASE_RESUME_MODE[$phase_id]}"
}

certification_selected_image_services() {
  local service inventory_entries

  inventory_entries="$(simplematch_local_image_inventory_entries)" || return 1
  while IFS='|' read -r _ service _ _; do
    [[ -n "$service" ]] || continue
    if [[ "${matching_fleet_only:-false}" == true && "$service" != matching ]]; then
      continue
    fi
    printf '%s\n' "$service"
  done <<<"$inventory_entries"
  return 0
}

certification_phase_dependencies() {
  local phase_id="${1:-}"
  local dependencies service selected_services

  _certification_require_known_phase "$phase_id" || return 1
  case "$phase_id" in
    kafka-profile-validation)
      printf '%s\n' \
        static-matching-profile \
        kafka-describe-matching-commands \
        kafka-config-matching-commands \
        kafka-describe-matching-events \
        kafka-config-matching-events \
        kafka-broker-config \
        kafka-capacity-evidence
      [[ -n "${SIMPLEMATCH_KAFKA_PRODUCER_CONFIG_FILE:-}" ]] || \
        printf '%s\n' kafka-producer-contract
      return 0
      ;;
    registry-connectivity)
      [[ "${skip_compose:-false}" == true ]] || \
        printf '%s\n' compose-down-before-kubernetes
      return 0
      ;;
    registry-publish/*)
      printf '%s\n' registry-connectivity
      if [[ "${skip_build:-false}" != true ]]; then
        printf 'local-image-build/%s\n' "${phase_id#registry-publish/}"
      fi
      return 0
      ;;
    registry-image-lock)
      printf '%s\n' local-image-inventory
      selected_services="$(certification_selected_image_services)" || return 1
      while IFS= read -r service; do
        [[ -n "$service" ]] && printf 'registry-publish/%s\n' "$service"
      done <<<"$selected_services"
      return 0
      ;;
    kind-load-import)
      printf '%s\n' local-image-inventory
      [[ "${skip_compose:-false}" == true ]] || \
        printf '%s\n' compose-down-before-kubernetes
      if [[ "${skip_build:-false}" != true ]]; then
        selected_services="$(certification_selected_image_services)" || return 1
        while IFS= read -r service; do
          [[ -n "$service" ]] && printf 'local-image-build/%s\n' "$service"
        done <<<"$selected_services"
      fi
      return 0
      ;;
    kubernetes-manifest-split)
      printf '%s\n' \
        static-kubernetes-overlays \
        static-kubernetes-dependencies \
        static-matching-manifests
      if [[ "${image_transport:-registry}" == registry ]]; then
        printf '%s\n' registry-image-lock
      else
        printf '%s\n' kind-load-import
      fi
      return 0
      ;;
    kubernetes-namespace)
      [[ "${skip_compose:-false}" == true ]] || \
        printf '%s\n' compose-down-before-kubernetes
      printf '%s\n' kubernetes-manifest-split
      return 0
      ;;
    kubernetes-open-barriers)
      if [[ "${matching_fleet_only:-false}" == true ]]; then
        printf '%s\n' kubernetes-topic-provisioning
      else
        printf '%s\n' kubernetes-migrations
      fi
      return 0
      ;;
    kubernetes-fleet)
      if [[ "${matching_fleet_only:-false}" == true ]]; then
        printf '%s\n' kubernetes-matching-workloads
      else
        printf '%s\n' kubernetes-workloads kubernetes-cdc-delivery
      fi
      return 0
      ;;
  esac

  dependencies="${SIMPLEMATCH_CERTIFICATION_PHASE_DEPENDENCIES[$phase_id]}"
  [[ -n "$dependencies" ]] || return 0
  tr ' ' '\n' <<<"$dependencies" | sed '/^$/d'
}

_certification_profile_root_phase_ids() {
  local service selected_services

  printf '%s\n' \
    static-kubernetes-overlays \
    static-phase1-deployment-contracts \
    static-kubernetes-dependencies \
    static-matching-manifests \
    static-matching-profile \
    static-flyway-services \
    compose-config \
    local-image-inventory

  if [[ "${skip_build:-false}" != true ]]; then
    selected_services="$(certification_selected_image_services)" || return 1
    while IFS= read -r service; do
      [[ -n "$service" ]] && printf 'local-image-build/%s\n' "$service"
    done <<<"$selected_services"
  fi

  if [[ "${skip_compose:-false}" != true ]]; then
    printf '%s\n' kafka-broker-failure-live
  fi

  if [[ "${skip_kubernetes:-false}" != true ]]; then
    if [[ "${matching_fleet_only:-false}" == true ]]; then
      printf '%s\n' kubernetes-fleet
    else
      printf '%s\n' retained-run-provenance
    fi
  fi
  return 0
}

_certification_collect_ordered_phase() {
  local phase_id="$1"
  local emitted_name="$2"
  local ordered_name="$3"
  local dependency dependency_output
  local -n emitted_map="$emitted_name"
  local -n ordered_list="$ordered_name"

  _certification_require_known_phase "$phase_id" || return 1
  [[ -z "${emitted_map[$phase_id]+x}" ]] || return 0

  dependency_output="$(certification_phase_dependencies "$phase_id")" || return 1
  while IFS= read -r dependency; do
    [[ -n "$dependency" ]] || continue
    _certification_collect_ordered_phase \
      "$dependency" "$emitted_name" "$ordered_name" || return 1
  done <<<"$dependency_output"

  emitted_map["$phase_id"]=true
  ordered_list+=("$phase_id")
}

certification_required_phase_ids() {
  local phase_id root_output
  local -A emitted=()
  local -a ordered=()

  certification_phase_validate_graph || return 1
  root_output="$(_certification_profile_root_phase_ids)" || return 1
  while IFS= read -r phase_id; do
    [[ -n "$phase_id" ]] || continue
    _certification_collect_ordered_phase "$phase_id" emitted ordered || return 1
  done <<<"$root_output"

  printf '%s\n' "${ordered[@]}"
}

_certification_emit_skip_difference() {
  local restored_output="$1"
  local previous_output="$2"
  local reason="$3"
  local phase_id
  local -A previous=()

  while IFS= read -r phase_id; do
    [[ -n "$phase_id" ]] && previous["$phase_id"]=true
  done <<<"$previous_output"
  while IFS= read -r phase_id; do
    [[ -n "$phase_id" ]] || continue
    if [[ -z "${previous[$phase_id]+x}" ]]; then
      printf '%s|%s\n' "$phase_id" "$reason"
    fi
  done <<<"$restored_output"
  return 0
}

certification_explicit_skip_entries() {
  local inherited_skip_build="${skip_build:-false}"
  local inherited_skip_compose="${skip_compose:-false}"
  local inherited_skip_kubernetes="${skip_kubernetes:-false}"
  local inherited_matching_fleet_only="${matching_fleet_only:-false}"
  local skip_build="$inherited_skip_build"
  local skip_compose="$inherited_skip_compose"
  local skip_kubernetes="$inherited_skip_kubernetes"
  local matching_fleet_only="$inherited_matching_fleet_only"
  local previous_output restored_output

  previous_output="$(certification_required_phase_ids)" || return 1

  if [[ "$skip_build" == true ]]; then
    skip_build=false
    restored_output="$(certification_required_phase_ids)" || return 1
    _certification_emit_skip_difference \
      "$restored_output" "$previous_output" 'operator set --skip-build' || return 1
    previous_output="$restored_output"
  fi
  if [[ "$skip_compose" == true ]]; then
    skip_compose=false
    restored_output="$(certification_required_phase_ids)" || return 1
    _certification_emit_skip_difference \
      "$restored_output" "$previous_output" 'operator set --skip-compose' || return 1
    previous_output="$restored_output"
  fi
  if [[ "$skip_kubernetes" == true ]]; then
    skip_kubernetes=false
    restored_output="$(certification_required_phase_ids)" || return 1
    _certification_emit_skip_difference \
      "$restored_output" "$previous_output" 'operator set --skip-kubernetes' || return 1
    previous_output="$restored_output"
  fi
  if [[ "$matching_fleet_only" == true ]]; then
    matching_fleet_only=false
    restored_output="$(certification_required_phase_ids)" || return 1
    _certification_emit_skip_difference \
      "$restored_output" "$previous_output" \
      'operator selected --matching-fleet-only' || return 1
  fi
  return 0
}

_certification_validate_phase_visit() {
  local phase_id="$1"
  local state_name="$2"
  local dependency state dependency_output
  local -a dependencies=()
  local -n state_map="$state_name"

  state="${state_map[$phase_id]:-unseen}"
  case "$state" in
    visited) return 0 ;;
    visiting)
      printf 'certification phase graph contains a dependency cycle at %s\n' \
        "$phase_id" >&2
      return 1
      ;;
  esac

  state_map["$phase_id"]=visiting
  dependency_output="$(certification_phase_dependencies "$phase_id")" || return 1
  if [[ -n "$dependency_output" ]]; then
    mapfile -t dependencies <<<"$dependency_output"
  fi
  for dependency in "${dependencies[@]}"; do
    [[ -n "$dependency" ]] || continue
    _certification_require_known_phase "$dependency" || {
      printf 'certification phase %s depends on unknown phase %s\n' \
        "$phase_id" "$dependency" >&2
      return 1
    }
    _certification_validate_phase_visit "$dependency" "$state_name" || return 1
  done
  state_map["$phase_id"]=visited
}

certification_phase_validate_graph() {
  local phase_id
  local -A states=()

  certification_phase_graph_initialize || return 1
  for phase_id in "${SIMPLEMATCH_CERTIFICATION_PHASE_ORDER[@]}"; do
    _certification_validate_phase_visit "$phase_id" states || return 1
  done
}
