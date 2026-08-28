#!/usr/bin/env bash

# Phase definitions for local production-like certification. This module owns
# dependency, selection, and reuse policy only; it does not execute phases or
# inspect cache state.

_phase_graph_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/lib/local-image-inventory.sh
source "$_phase_graph_dir/local-image-inventory.sh"
unset _phase_graph_dir

declare -gA SIMPLEMATCH_CERTIFICATION_PHASE_POLICY=()
declare -gA SIMPLEMATCH_CERTIFICATION_PHASE_DEPENDENCIES=()
declare -gA SIMPLEMATCH_CERTIFICATION_PHASE_VERSION=()
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
  local dependencies="${3:-}"
  local definition_version="${4:-1}"

  _certification_require_phase_id "$phase_id" || return 1
  case "$policy" in
    FRESH|CONTENT_ADDRESSED|REVALIDATE) ;;
    *)
      printf 'invalid certification phase policy for %s: %s\n' \
        "$phase_id" "$policy" >&2
      return 1
      ;;
  esac
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
  SIMPLEMATCH_CERTIFICATION_PHASE_DEPENDENCIES["$phase_id"]="$dependencies"
  SIMPLEMATCH_CERTIFICATION_PHASE_VERSION["$phase_id"]="$definition_version"
  SIMPLEMATCH_CERTIFICATION_PHASE_ORDER+=("$phase_id")
}

_certification_register_fixed_phases() {
  local phase_id policy dependencies

  while IFS='|' read -r phase_id policy dependencies; do
    [[ -n "$phase_id" ]] || continue
    _certification_register_phase "$phase_id" "$policy" "$dependencies" || return 1
  done <<'EOF_PHASES'
source-preflight|FRESH|
static-kubernetes-overlays|CONTENT_ADDRESSED|source-preflight
static-kubernetes-dependencies|CONTENT_ADDRESSED|source-preflight
static-matching-manifests|CONTENT_ADDRESSED|source-preflight
static-matching-profile|CONTENT_ADDRESSED|source-preflight
static-flyway-services|CONTENT_ADDRESSED|source-preflight
compose-config|CONTENT_ADDRESSED|source-preflight
local-image-inventory|CONTENT_ADDRESSED|source-preflight
kafka-producer-contract|CONTENT_ADDRESSED|static-matching-profile
compose-up|FRESH|compose-config
compose-wait|FRESH|compose-up
compose-status|FRESH|compose-wait
kafka-capacity-evidence|FRESH|compose-wait
kafka-create-matching-commands|FRESH|compose-wait
kafka-create-matching-events|FRESH|compose-wait
kafka-create-account-lifecycle|FRESH|compose-wait
kafka-create-marketdata-events|FRESH|compose-wait
kafka-describe-matching-commands|FRESH|kafka-create-matching-commands
kafka-config-matching-commands|FRESH|kafka-create-matching-commands
kafka-describe-matching-events|FRESH|kafka-create-matching-events
kafka-config-matching-events|FRESH|kafka-create-matching-events
kafka-broker-config|FRESH|compose-wait
kafka-profile-validation|FRESH|
kafka-broker-failure-live|FRESH|kafka-profile-validation
compose-down-before-kubernetes|FRESH|kafka-broker-failure-live
registry-connectivity|FRESH|
registry-image-lock|CONTENT_ADDRESSED|
kind-load-import|FRESH|
kubernetes-manifest-split|FRESH|
kubernetes-namespace|FRESH|
kubernetes-inputs|FRESH|kubernetes-namespace
kubernetes-platform-apply|FRESH|kubernetes-inputs
kubernetes-migrations|FRESH|kubernetes-platform-apply static-flyway-services
kubernetes-matching-manifest|FRESH|kubernetes-platform-apply
kubernetes-topic-provisioning|FRESH|kubernetes-platform-apply
kubernetes-open-barriers|FRESH|
kubernetes-workload-apply|FRESH|kubernetes-open-barriers
kubernetes-matching-apply|FRESH|kubernetes-open-barriers
kubernetes-risk-outbox-connector|FRESH|kubernetes-workload-apply
kubernetes-workloads|FRESH|kubernetes-risk-outbox-connector
kubernetes-matching-workloads|FRESH|kubernetes-matching-apply
kubernetes-fleet|FRESH|
retained-run-provenance|FRESH|kubernetes-fleet
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
      "local-image-build/$service" CONTENT_ADDRESSED local-image-inventory || return 1
    _certification_register_phase "registry-publish/$service" REVALIDATE || return 1
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
        printf '%s\n' kubernetes-workloads
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

  # Static/configuration checks are deliberate roots because partial profiles
  # still execute them even when Compose and Kubernetes are explicitly skipped.
  printf '%s\n' \
    static-kubernetes-overlays \
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

_certification_collect_dependency_closure() {
  local phase_id="$1"
  local selected_name="$2"
  local dependency dependency_output
  local -n selected_map="$selected_name"

  _certification_require_known_phase "$phase_id" || return 1
  [[ -z "${selected_map[$phase_id]+x}" ]] || return 0
  selected_map["$phase_id"]=true

  dependency_output="$(certification_phase_dependencies "$phase_id")" || return 1
  while IFS= read -r dependency; do
    [[ -n "$dependency" ]] || continue
    _certification_collect_dependency_closure "$dependency" "$selected_name" || return 1
  done <<<"$dependency_output"
}

certification_required_phase_ids() {
  local phase_id root_output
  local -A selected=()

  certification_phase_validate_graph || return 1
  root_output="$(_certification_profile_root_phase_ids)" || return 1
  while IFS= read -r phase_id; do
    [[ -n "$phase_id" ]] || continue
    _certification_collect_dependency_closure "$phase_id" selected || return 1
  done <<<"$root_output"

  for phase_id in "${SIMPLEMATCH_CERTIFICATION_PHASE_ORDER[@]}"; do
    if [[ -n "${selected[$phase_id]+x}" ]]; then
      printf '%s\n' "$phase_id"
    fi
  done
  return 0
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
