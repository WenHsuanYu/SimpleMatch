#!/usr/bin/env bash

# Host-capacity guard for Docker Desktop production-like work. This module
# intentionally checks only the two host conditions Docker cannot guarantee:
# current usable headroom and a virtual-disk limit that fits its filesystem.

SIMPLEMATCH_DOCKER_STORAGE_MAX_PERCENT=75
SIMPLEMATCH_DOCKER_STORAGE_MIN_AVAILABLE_BYTES=$((40 * 1024 * 1024 * 1024))

simplematch_docker_storage_capacity_check() {
  local filesystem_bytes="$1"
  local available_bytes="$2"
  local disk_limit_bytes="$3"
  local maximum_limit_bytes

  [[ "$filesystem_bytes" =~ ^[1-9][0-9]*$ ]] || return 2
  [[ "$available_bytes" =~ ^[0-9]+$ ]] || return 2
  [[ "$disk_limit_bytes" =~ ^[1-9][0-9]*$ ]] || return 2

  maximum_limit_bytes=$((
    filesystem_bytes * SIMPLEMATCH_DOCKER_STORAGE_MAX_PERCENT / 100
  ))
  if (( disk_limit_bytes > maximum_limit_bytes )); then
    printf 'Docker Desktop disk limit %s exceeds %s%% of its %s-byte host filesystem; maximum safe limit is %s bytes.\n' \
      "$disk_limit_bytes" "$SIMPLEMATCH_DOCKER_STORAGE_MAX_PERCENT" \
      "$filesystem_bytes" "$maximum_limit_bytes" >&2
    return 1
  fi
  if (( available_bytes < SIMPLEMATCH_DOCKER_STORAGE_MIN_AVAILABLE_BYTES )); then
    printf 'Docker Desktop host filesystem has %s available bytes; at least %s are required before production-like work.\n' \
      "$available_bytes" "$SIMPLEMATCH_DOCKER_STORAGE_MIN_AVAILABLE_BYTES" >&2
    return 1
  fi
}

simplematch_docker_storage_preflight() {
  local context settings_file data_folder disk_size_mib docker_raw
  local filesystem_bytes available_bytes disk_limit_bytes

  context="$(docker context show)" || return 1
  if [[ "$context" != desktop-linux ]]; then
    printf 'Docker host-capacity preflight: not applicable to context %s.\n' "$context"
    return 0
  fi

  settings_file="${SIMPLEMATCH_DOCKER_DESKTOP_SETTINGS_FILE:-$HOME/.docker/desktop/settings-store.json}"
  [[ -r "$settings_file" ]] || {
    printf 'Docker Desktop settings are not readable: %s\n' "$settings_file" >&2
    return 1
  }
  data_folder="$(jq -er '.DataFolder | select(type == "string" and length > 0)' \
    "$settings_file")" || {
    printf 'Docker Desktop DataFolder is missing from %s.\n' "$settings_file" >&2
    return 1
  }
  disk_size_mib="$(jq -er '.DiskSizeMiB | select(type == "number" and floor == . and . > 0)' \
    "$settings_file")" || {
    printf 'Docker Desktop DiskSizeMiB is missing from %s.\n' "$settings_file" >&2
    return 1
  }
  docker_raw="$data_folder/Docker.raw"
  [[ -f "$docker_raw" ]] || {
    printf 'Docker Desktop disk image does not exist: %s\n' "$docker_raw" >&2
    return 1
  }

  read -r filesystem_bytes available_bytes < <(
    df -B1 --output=size,avail "$docker_raw" | awk 'NR == 2 {print $1, $2}'
  )
  [[ "$filesystem_bytes" =~ ^[1-9][0-9]*$ && "$available_bytes" =~ ^[0-9]+$ ]] || {
    printf 'Docker Desktop host filesystem capacity could not be measured.\n' >&2
    return 1
  }
  disk_limit_bytes=$((disk_size_mib * 1024 * 1024))

  simplematch_docker_storage_capacity_check \
    "$filesystem_bytes" "$available_bytes" "$disk_limit_bytes" || return 1
  printf 'Docker host-capacity preflight passed: available=%s bytes, disk-limit=%s bytes, filesystem=%s bytes.\n' \
    "$available_bytes" "$disk_limit_bytes" "$filesystem_bytes"
}
