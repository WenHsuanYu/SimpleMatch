#!/usr/bin/env bash

# Canonical SimpleMatch local application image inventory.
#
# This module is intentionally side-effect free. Build and registry publication
# consume the same inventory instead of parsing each other's CLI output. Each entry is:
#
#   image-class|service|build-source|repository
#
# The tag is supplied by the caller because it is run-specific state, not part
# of the canonical inventory.

SIMPLEMATCH_LOCAL_IMAGE_INVENTORY=(
  'spring|account-service|:services:account-service|simplematch/account-service'
  'spring|risk-service|:services:risk-service|simplematch/risk-service'
  'spring|persistence|:services:persistence|simplematch/persistence'
  'spring|market-data-projection|:services:market-data-projection|simplematch/market-data-projection'
  'spring|marketdata-publisher|:services:marketdata-publisher|simplematch/marketdata-publisher'
  'spring|marketdata-streamer|:services:marketdata-streamer|simplematch/marketdata-streamer'
  'spring|query-service|:services:query-service|simplematch/query-service'
  'spring|quickfix-gateway|:services:quickfix-gateway|quickfix-gateway'
  'flyway|flyway-runner|deploy/docker/Dockerfile.flyway-runner|simplematch/flyway-runner'
  'verification|risk-matching-e2e-verifier|deploy/docker/Dockerfile.risk-matching-e2e-verifier|simplematch/risk-matching-e2e-verifier'
  'native|matching|deploy/docker/Dockerfile.matching|simplematch-matching'
)

simplematch_local_image_tag_validate() {
  local tag="$1"
  [[ "$tag" =~ ^[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}$ ]] || {
    printf 'invalid local image tag: %s\n' "$tag" >&2
    return 1
  }
}

simplematch_local_image_inventory_validate() {
  local entry field_count image_class service build_source repository
  local -A seen_services=()
  local -A seen_repositories=()

  ((${#SIMPLEMATCH_LOCAL_IMAGE_INVENTORY[@]} > 0)) || {
    printf '%s\n' 'local image inventory is empty' >&2
    return 1
  }

  for entry in "${SIMPLEMATCH_LOCAL_IMAGE_INVENTORY[@]}"; do
    field_count="$(awk -F'|' '{print NF}' <<<"$entry")"
    [[ "$field_count" -eq 4 ]] || {
      printf 'local image inventory entry must contain exactly four fields: %s\n' "$entry" >&2
      return 1
    }
    IFS='|' read -r image_class service build_source repository <<<"$entry"
    [[ -n "$image_class" && -n "$service" && -n "$build_source" && -n "$repository" ]] || {
      printf 'malformed local image inventory entry: %s\n' "$entry" >&2
      return 1
    }
    [[ "$service" =~ ^[a-z0-9][a-z0-9-]*$ ]] || {
      printf 'invalid service name in local image inventory: %s\n' "$service" >&2
      return 1
    }
    case "$image_class" in
      spring|flyway|verification|native) ;;
      *)
        printf 'unsupported local image class %s for service %s\n' "$image_class" "$service" >&2
        return 1
        ;;
    esac
    [[ "$repository" =~ ^[a-z0-9]+([._-][a-z0-9]+)*(\/[a-z0-9]+([._-][a-z0-9]+)*)*$ ]] || {
      printf 'invalid untagged repository in local image inventory for service %s: %s\n' "$service" "$repository" >&2
      return 1
    }
    [[ -z "${seen_services[$service]+x}" ]] || {
      printf 'duplicate service in local image inventory: %s\n' "$service" >&2
      return 1
    }
    [[ -z "${seen_repositories[$repository]+x}" ]] || {
      printf 'duplicate repository in local image inventory: %s\n' "$repository" >&2
      return 1
    }
    seen_services["$service"]=1
    seen_repositories["$repository"]=1
  done
}

simplematch_local_image_inventory_entries() {
  simplematch_local_image_inventory_validate || return 1
  printf '%s\n' "${SIMPLEMATCH_LOCAL_IMAGE_INVENTORY[@]}"
}

simplematch_local_image_inventory_emit() {
  local tag="$1"
  local entry image_class service build_source repository

  simplematch_local_image_tag_validate "$tag" || return 1
  simplematch_local_image_inventory_validate || return 1
  for entry in "${SIMPLEMATCH_LOCAL_IMAGE_INVENTORY[@]}"; do
    IFS='|' read -r image_class service build_source repository <<<"$entry"
    printf '%s|%s|%s|%s:%s\n' "$image_class" "$service" "$build_source" "$repository" "$tag"
  done
}

simplematch_local_image_inventory_has_service() {
  local wanted="$1"
  local entry image_class service build_source repository

  simplematch_local_image_inventory_validate || return 1
  for entry in "${SIMPLEMATCH_LOCAL_IMAGE_INVENTORY[@]}"; do
    IFS='|' read -r image_class service build_source repository <<<"$entry"
    [[ "$service" == "$wanted" ]] && return 0
  done
  return 1
}

simplematch_local_image_inventory_entry() {
  local wanted="$1"
  local entry image_class service build_source repository

  simplematch_local_image_inventory_validate || return 1
  for entry in "${SIMPLEMATCH_LOCAL_IMAGE_INVENTORY[@]}"; do
    IFS='|' read -r image_class service build_source repository <<<"$entry"
    if [[ "$service" == "$wanted" ]]; then
      printf '%s\n' "$entry"
      return 0
    fi
  done
  printf 'unknown local image service: %s\n' "$wanted" >&2
  return 1
}

simplematch_local_image_inventory_repository() {
  local entry
  entry="$(simplematch_local_image_inventory_entry "$1")" || return 1
  printf '%s\n' "${entry##*|}"
}

# The repository local overlay contains the normal application, Flyway, and
# Matching images. Verification images are built/published from the same
# inventory but are applied only by explicit verification workflows outside
# deploy/k8s/overlays/local.
simplematch_local_image_inventory_local_overlay_services() {
  local entry image_class service build_source repository

  simplematch_local_image_inventory_validate || return 1
  for entry in "${SIMPLEMATCH_LOCAL_IMAGE_INVENTORY[@]}"; do
    IFS='|' read -r image_class service build_source repository <<<"$entry"
    [[ "$image_class" == verification ]] && continue
    printf '%s\n' "$service"
  done
}

simplematch_local_image_inventory_validate_selection() {
  local service
  local -A seen=()

  for service in "$@"; do
    simplematch_local_image_inventory_has_service "$service" || {
      printf 'unknown local image service: %s\n' "$service" >&2
      return 1
    }
    [[ -z "${seen[$service]+x}" ]] || {
      printf 'duplicate local image service selection: %s\n' "$service" >&2
      return 1
    }
    seen["$service"]=1
  done
}

simplematch_local_image_inventory_service_selected() {
  local candidate="$1"
  shift
  (($# == 0)) && return 0
  local wanted
  for wanted in "$@"; do
    [[ "$wanted" == "$candidate" ]] && return 0
  done
  return 1
}

simplematch_local_image_inventory_source_image() {
  local wanted="$1"
  local tag="$2"
  local repository

  simplematch_local_image_tag_validate "$tag" || return 1
  repository="$(simplematch_local_image_inventory_repository "$wanted")" || return 1
  printf '%s:%s\n' "$repository" "$tag"
}

simplematch_local_image_inventory_images() {
  local tag="$1"
  local entry image_class service build_source repository

  simplematch_local_image_tag_validate "$tag" || return 1
  simplematch_local_image_inventory_validate || return 1
  for entry in "${SIMPLEMATCH_LOCAL_IMAGE_INVENTORY[@]}"; do
    IFS='|' read -r image_class service build_source repository <<<"$entry"
    printf '%s:%s\n' "$repository" "$tag"
  done
}
