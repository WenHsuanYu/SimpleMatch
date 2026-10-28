#!/usr/bin/env bash

# Fresh registry image locks. No image-build result cache or output materialization.

declare -g evidence_dir image_lock registry_fragment_directory matching_fleet_only

certification_validate_image_lock() {
  local directory="$1" lock="$2" expected actual

  [[ -f "$lock" && ! -L "$lock" && -f "$directory/image-lock.sha256" && \
    ! -L "$directory/image-lock.sha256" ]] || return 1
  simplematch_local_image_lock_validate_file "$lock" || return 1
  expected="$(cat "$directory/image-lock.sha256")" || return 1
  [[ "$expected" =~ ^[0-9a-f]{64}$ ]] || return 1
  actual="$(sha256sum "$lock" | awk '{print $1}')" || return 1
  [[ "$actual" == "$expected" ]]
}

certification_construct_registry_image_lock() {
  local service fragment_file profile expected_profile temp_lock
  local selected_services

  selected_services="$(certification_selected_image_services)" || return 1
  mkdir -p "$(dirname -- "$image_lock")" || return 1
  temp_lock="$(mktemp "${image_lock}.tmp.XXXXXX")" || return 1
  while IFS= read -r service; do
    [[ -n "$service" ]] || continue
    fragment_file="$registry_fragment_directory/${service}.lock"
    [[ -f "$fragment_file" ]] || {
      printf 'registry image-lock fragment is missing for %s: %s\n' \
        "$service" "$fragment_file" >&2
      rm -f -- "$temp_lock"
      return 1
    }
    simplematch_local_image_lock_entry "$fragment_file" "$service" >>"$temp_lock" || {
      rm -f -- "$temp_lock"
      return 1
    }
  done <<<"$selected_services"

  simplematch_local_image_lock_validate_file "$temp_lock" || {
    rm -f -- "$temp_lock"
    return 1
  }
  profile="$(simplematch_local_image_lock_render_profile "$temp_lock")" || {
    rm -f -- "$temp_lock"
    return 1
  }
  if [[ "$matching_fleet_only" == true ]]; then
    expected_profile=matching-only
  else
    expected_profile=full
  fi
  [[ "$profile" == "$expected_profile" ]] || {
    printf 'registry image lock profile mismatch: expected %s, got %s\n' \
      "$expected_profile" "$profile" >&2
    rm -f -- "$temp_lock"
    return 1
  }
  mv -f -- "$temp_lock" "$image_lock" || return 1
  sha256sum "$image_lock" | awk '{print $1}' >"$evidence_dir/image-lock.sha256"
}
