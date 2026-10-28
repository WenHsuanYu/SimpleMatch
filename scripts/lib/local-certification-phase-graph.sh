#!/usr/bin/env bash

# Phase definitions for local production-like certification. This module owns
# dependencies, active-profile selection, and execution order. It does not cache
# phase results or execute commands.

_phase_graph_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/lib/local-image-inventory.sh
source "$_phase_graph_dir/local-image-inventory.sh"
unset _phase_graph_dir

declare -gA SIMPLEMATCH_CERTIFICATION_PHASE_DEPENDENCIES=()
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
  local phase_id="$1" dependencies="${2:-}"

  _certification_require_phase_id "$phase_id" || return 1
  [[ -z "${SIMPLEMATCH_CERTIFICATION_PHASE_DEPENDENCIES[$phase_id]+x}" ]] || {
    printf 'duplicate certification phase id: %s\n' "$phase_id" >&2
    return 1
  }
  SIMPLEMATCH_CERTIFICATION_PHASE_DEPENDENCIES["$phase_id"]="$dependencies"
  SIMPLEMATCH_CERTIFICATION_PHASE_ORDER+=("$phase_id")
}

_certification_register_fixed_phases() {
  local phase_id dependencies

  while IFS='|' read -r phase_id dependencies; do
    [[ -n "$phase_id" ]] || continue
    _certification_register_phase "$phase_id" "$dependencies" || return 1
  done <<'EOF_PHASES'
source-preflight|
local-resource-budget|static-kubernetes-overlays
static-kubernetes-overlays|source-preflight
static-phase1-deployment-contracts|source-preflight
static-kubernetes-dependencies|source-preflight
static-matching-manifests|source-preflight
static-matching-profile|source-preflight
static-flyway-services|source-preflight
compose-config|source-preflight
cdc-outbox-failure-live|static-phase1-deployment-contracts compose-config
local-image-inventory|source-preflight
kafka-producer-contract|static-matching-profile
compose-up|compose-config cdc-outbox-failure-live
compose-wait|compose-up
compose-status|compose-wait
kafka-capacity-evidence|compose-wait
kafka-create-matching-commands|compose-wait
kafka-create-matching-events|compose-wait
kafka-create-account-lifecycle|compose-wait
kafka-create-marketdata-events|compose-wait
kafka-describe-matching-commands|kafka-create-matching-commands
kafka-config-matching-commands|kafka-create-matching-commands
kafka-describe-matching-events|kafka-create-matching-events
kafka-config-matching-events|kafka-create-matching-events
kafka-broker-config|compose-wait
kafka-profile-validation|
kafka-broker-failure-live|kafka-profile-validation
compose-down-before-kubernetes|kafka-broker-failure-live
registry-connectivity|
registry-image-lock|
kind-load-import|
kubernetes-manifest-split|
kubernetes-namespace|
kubernetes-inputs|kubernetes-namespace static-phase1-deployment-contracts
kubernetes-platform-apply|kubernetes-inputs
kubernetes-migrations|kubernetes-platform-apply static-flyway-services kubernetes-topic-provisioning
kubernetes-matching-manifest|kubernetes-platform-apply
kubernetes-topic-provisioning|kubernetes-platform-apply
kubernetes-open-barriers|
kubernetes-connect-apply|kubernetes-migrations kubernetes-topic-provisioning
kubernetes-workload-apply|kubernetes-open-barriers
kubernetes-matching-apply|kubernetes-open-barriers kubernetes-matching-manifest
kubernetes-risk-outbox-connector|kubernetes-connect-apply
kubernetes-account-outbox-connector|kubernetes-connect-apply
kubernetes-workloads|kubernetes-workload-apply kubernetes-risk-outbox-connector kubernetes-account-outbox-connector
kubernetes-cdc-delivery|kubernetes-workloads
kubernetes-matching-workloads|kubernetes-matching-apply
kubernetes-fleet|
retained-run-provenance|kubernetes-fleet
query-security-lifecycle|local-image-build/query-service
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
      "local-image-build/$service" local-image-inventory || return 1
    _certification_register_phase \
      "registry-publish/$service" || return 1
  done <<<"$inventory_entries"
  SIMPLEMATCH_CERTIFICATION_PHASE_GRAPH_INITIALIZED=true
}

_certification_require_known_phase() {
  local phase_id="${1:-}"

  _certification_require_phase_id "$phase_id" || return 1
  certification_phase_graph_initialize || return 1
  [[ -n "${SIMPLEMATCH_CERTIFICATION_PHASE_DEPENDENCIES[$phase_id]+x}" ]] || {
    printf 'unknown certification phase: %s\n' "$phase_id" >&2
    return 1
  }
}

certification_phase_ids() {
  certification_phase_graph_initialize || return 1
  printf '%s\n' "${SIMPLEMATCH_CERTIFICATION_PHASE_ORDER[@]}"
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
        local-resource-budget \
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
        printf '%s\n' \
          kubernetes-migrations \
          kubernetes-topic-provisioning \
          kubernetes-connect-apply
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

  if [[ "${query_security_lifecycle_only:-false}" == true ]]; then
    printf '%s\n' query-security-lifecycle
    return 0
  fi

  printf '%s\n' \
    static-kubernetes-overlays \
    static-phase1-deployment-contracts \
    static-kubernetes-dependencies \
    static-matching-manifests \
    static-matching-profile \
    static-flyway-services \
    compose-config \
    local-image-inventory

  [[ "${skip_kubernetes:-false}" == true ]] || printf '%s\n' local-resource-budget

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
