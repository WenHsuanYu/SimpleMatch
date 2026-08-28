#!/usr/bin/env bash

# Image build/publication adapters used by incremental certification. Reuse
# policy remains in the planner; this module validates and materializes only
# Docker, registry, and image-lock outputs.

_certification_images_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/lib/local-image-inventory.sh
source "$_certification_images_dir/local-image-inventory.sh"
# shellcheck source=scripts/lib/local-image-transport.sh
source "$_certification_images_dir/local-image-transport.sh"
unset _certification_images_dir

registry_fragment_directory=""

certification_source_image_identity() {
  local service="$1"
  local source_image identity

  source_image="$(simplematch_local_image_inventory_source_image "$service" "$image_tag")" || return 1
  identity="$(docker image inspect --format '{{.Id}}' "$source_image" 2>/dev/null)" || return 1
  [[ "$identity" =~ ^sha256:[0-9a-f]{64}$ ]] || return 1
  printf '%s\n' "$identity"
}

certification_registry_digest_available() {
  local digest_reference="$1"
  local endpoint registry_repository repository digest

  endpoint="$(simplematch_registry_endpoint)" || return 1
  registry_repository="${digest_reference%@*}"
  digest="${digest_reference##*@}"
  [[ "$digest_reference" =~ @sha256:[0-9a-f]{64}$ ]] || return 1
  [[ "$registry_repository" == "$endpoint/"* ]] || return 1
  repository="${registry_repository#"$endpoint/"}"
  [[ -n "$repository" ]] || return 1

  curl --fail --silent --show-error --head \
    -H 'Accept: application/vnd.oci.image.manifest.v1+json, application/vnd.oci.image.index.v1+json, application/vnd.docker.distribution.manifest.v2+json, application/vnd.docker.distribution.manifest.list.v2+json' \
    "http://${endpoint}/v2/${repository}/manifests/${digest}" >/dev/null
}

_certification_image_lock_payload() {
  local object_path="$1"
  jq -er '
    [.outputs[] | select(.kind == "image-lock" and .name == "local-images")] as $matches |
    if ($matches | length) == 1 then $matches[0] else error("image lock output mismatch") end
  ' "$object_path"
}

_certification_image_lock_payload_valid() {
  local object_path="$1"
  local payload identity content_base64 actual_digest temp_lock

  payload="$(_certification_image_lock_payload "$object_path")" || return 1
  identity="$(jq -r '.identity' <<<"$payload")" || return 1
  content_base64="$(jq -r '.contentBase64 // empty' <<<"$payload")" || return 1
  [[ "$identity" =~ ^sha256:[0-9a-f]{64}$ && -n "$content_base64" ]] || return 1

  temp_lock="$(mktemp)" || return 1
  if ! printf '%s' "$content_base64" | base64 --decode >"$temp_lock" 2>/dev/null; then
    rm -f -- "$temp_lock"
    return 1
  fi
  actual_digest="$(sha256sum "$temp_lock" | awk '{print "sha256:" $1}')" || {
    rm -f -- "$temp_lock"
    return 1
  }
  [[ "$actual_digest" == "$identity" ]] || {
    rm -f -- "$temp_lock"
    return 1
  }
  simplematch_local_image_lock_validate_file "$temp_lock" || {
    rm -f -- "$temp_lock"
    return 1
  }
  rm -f -- "$temp_lock"
}

_certification_image_current_result_output() {
  local result_path="$1"
  local kind="$2"
  local name="$3"

  jq -er --arg kind "$kind" --arg name "$name" '
    [.outputs[] | select(.kind == $kind and .name == $name)] as $matches |
    if ($matches | length) == 1 then $matches[0] else error("image output mismatch") end
  ' "$result_path"
}

certification_image_phase_cached_outputs_valid() {
  local phase_id="$1"
  local evidence_digest="$2"
  local service identity location expected_location actual_identity object_path

  case "$phase_id" in
    local-image-build/*)
      service="${phase_id#local-image-build/}"
      identity="$(certification_evidence_output_identity \
        "$evidence_digest" docker-image "$service")" || return 1
      location="$(certification_evidence_output_location \
        "$evidence_digest" docker-image "$service")" || return 1
      expected_location="$(
        simplematch_local_image_inventory_source_image "$service" "$image_tag"
      )" || return 1
      [[ "$location" == "$expected_location" ]] || return 1
      actual_identity="$(docker image inspect --format '{{.Id}}' "$location" 2>/dev/null)" || return 1
      [[ "$actual_identity" == "$identity" ]]
      ;;
    registry-image-lock)
      object_path="$(_certification_evidence_object_path "$evidence_digest")" || return 1
      certification_evidence_validate_object "$object_path" "$evidence_digest" || return 1
      _certification_image_lock_payload_valid "$object_path"
      ;;
    *)
      return 0
      ;;
  esac
}

certification_image_phase_current_outputs_valid() {
  local phase_id="$1"
  local result_path="$2"
  local service payload identity location expected_location actual_identity

  case "$phase_id" in
    local-image-build/*)
      service="${phase_id#local-image-build/}"
      payload="$(_certification_image_current_result_output \
        "$result_path" docker-image "$service")" || return 1
      identity="$(jq -r '.identity' <<<"$payload")" || return 1
      location="$(jq -r '.location' <<<"$payload")" || return 1
      expected_location="$(
        simplematch_local_image_inventory_source_image "$service" "$image_tag"
      )" || return 1
      [[ "$location" == "$expected_location" ]] || return 1
      actual_identity="$(docker image inspect --format '{{.Id}}' "$location" 2>/dev/null)" || return 1
      [[ "$actual_identity" == "$identity" ]]
      ;;
    registry-image-lock)
      [[ -f "$image_lock" ]] || return 1
      payload="$(_certification_image_current_result_output \
        "$result_path" image-lock local-images)" || return 1
      identity="$(jq -r '.identity' <<<"$payload")" || return 1
      [[ "$identity" =~ ^sha256:[0-9a-f]{64}$ ]] || return 1
      simplematch_local_image_lock_validate_file "$image_lock" || return 1
      actual_identity="$(sha256sum "$image_lock" | awk '{print "sha256:" $1}')" || return 1
      [[ "$actual_identity" == "$identity" ]]
      ;;
    *)
      return 0
      ;;
  esac
}

certification_image_phase_revalidate() {
  local phase_id="$1"
  local evidence_digest="$2"
  local service identity location

  case "$phase_id" in
    registry-publish/*)
      service="${phase_id#registry-publish/}"
      identity="$(certification_evidence_output_identity \
        "$evidence_digest" registry-image "$service")" || return 1
      location="$(certification_evidence_output_location \
        "$evidence_digest" registry-image "$service")" || return 1
      [[ "$location" == *"@$identity" ]] || return 1
      certification_registry_digest_available "$location"
      ;;
    *)
      return 1
      ;;
  esac
}

_certification_registry_fragment_output() {
  local phase_id="$1"
  local service fragment_file entry digest_reference digest

  service="${phase_id#registry-publish/}"
  fragment_file="$registry_fragment_directory/${service}.lock"
  [[ -f "$fragment_file" ]] || return 1
  simplematch_local_image_lock_validate_file "$fragment_file" || return 1
  entry="$(simplematch_local_image_lock_entry "$fragment_file" "$service")" || return 1
  digest_reference="${entry##*|}"
  digest="${digest_reference##*@}"
  jq -cn \
    --arg name "$service" \
    --arg identity "$digest" \
    --arg location "$digest_reference" \
    --arg entry "$entry" \
    '[{kind:"registry-image",name:$name,identity:$identity,location:$location,entry:$entry}]'
}

_certification_image_lock_output() {
  local content_base64 digest

  [[ -f "$image_lock" ]] || return 1
  simplematch_local_image_lock_validate_file "$image_lock" || return 1
  digest="$(sha256sum "$image_lock" | awk '{print "sha256:" $1}')" || return 1
  content_base64="$(base64 <"$image_lock" | tr -d '\n')" || return 1
  [[ -n "$content_base64" ]] || return 1
  jq -cn \
    --arg identity "$digest" \
    --arg location "${image_lock#$repo_root/}" \
    --arg contentBase64 "$content_base64" \
    '[{kind:"image-lock",name:"local-images",identity:$identity,location:$location,contentBase64:$contentBase64}]'
}

certification_image_phase_outputs_json() {
  local phase_id="$1"
  local service source_image identity

  case "$phase_id" in
    local-image-build/*)
      service="${phase_id#local-image-build/}"
      source_image="$(simplematch_local_image_inventory_source_image "$service" "$image_tag")" || return 1
      identity="$(certification_source_image_identity "$service")" || return 1
      jq -cn \
        --arg name "$service" --arg identity "$identity" --arg location "$source_image" \
        '[{kind:"docker-image",name:$name,identity:$identity,location:$location}]'
      ;;
    registry-publish/*)
      _certification_registry_fragment_output "$phase_id"
      ;;
    registry-image-lock)
      _certification_image_lock_output
      ;;
    *)
      printf '%s\n' '[]'
      ;;
  esac
}

certification_image_phase_materialize_reused_outputs() {
  local phase_id="$1"
  local evidence_digest="$2"
  local object_path service entry content_base64 temp_path destination

  object_path="$(_certification_evidence_object_path "$evidence_digest")" || return 1
  certification_evidence_validate_object "$object_path" "$evidence_digest" || return 1

  case "$phase_id" in
    registry-publish/*)
      service="${phase_id#registry-publish/}"
      entry="$(
        jq -er --arg name "$service" '
          .outputs[] | select(.kind == "registry-image" and .name == $name) | .entry
        ' "$object_path"
      )" || return 1
      destination="$registry_fragment_directory/${service}.lock"
      mkdir -p "$registry_fragment_directory" || return 1
      temp_path="$(mktemp "${destination}.tmp.XXXXXX")" || return 1
      printf '%s\n' "$entry" >"$temp_path" || {
        rm -f -- "$temp_path"
        return 1
      }
      simplematch_local_image_lock_validate_file "$temp_path" || {
        rm -f -- "$temp_path"
        return 1
      }
      mv -f -- "$temp_path" "$destination" || {
        rm -f -- "$temp_path"
        return 1
      }
      ;;
    registry-image-lock)
      _certification_image_lock_payload_valid "$object_path" || return 1
      content_base64="$(
        jq -er '.outputs[] | select(.kind == "image-lock" and .name == "local-images") | .contentBase64' \
          "$object_path"
      )" || return 1
      mkdir -p "$(dirname -- "$image_lock")" || return 1
      temp_path="$(mktemp "${image_lock}.tmp.XXXXXX")" || return 1
      if ! printf '%s' "$content_base64" | base64 --decode >"$temp_path" 2>/dev/null; then
        rm -f -- "$temp_path"
        return 1
      fi
      simplematch_local_image_lock_validate_file "$temp_path" || {
        rm -f -- "$temp_path"
        return 1
      }
      mv -f -- "$temp_path" "$image_lock" || {
        rm -f -- "$temp_path"
        return 1
      }
      ;;
    *)
      return 0
      ;;
  esac
}

certification_build_local_images() {
  local service selected_services

  selected_services="$(certification_selected_image_services)" || return 1
  while IFS= read -r service; do
    [[ -n "$service" ]] || continue
    run_logged "local-image-build/$service" \
      bash "$repo_root/scripts/build-local-images.sh" \
      --tag "$image_tag" --service "$service" || return 1
  done <<<"$selected_services"
}

certification_publish_registry_images() {
  local service fragment_file selected_services

  registry_fragment_directory="$evidence_dir/image-lock-fragments"
  if [[ "${dry_run:-false}" != true ]]; then
    mkdir -p "$registry_fragment_directory" || return 1
  fi
  run_logged registry-connectivity \
    simplematch_registry_verify "$kind_cluster" || return 1

  selected_services="$(certification_selected_image_services)" || return 1
  while IFS= read -r service; do
    [[ -n "$service" ]] || continue
    fragment_file="$registry_fragment_directory/${service}.lock"
    run_logged "registry-publish/$service" \
      bash "$repo_root/scripts/publish-local-images.sh" \
      --tag "$image_tag" --service "$service" \
      --output "$fragment_file" || return 1
  done <<<"$selected_services"

  run_logged registry-image-lock \
    certification_construct_registry_image_lock || return 1
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
  mv -f -- "$temp_lock" "$image_lock"
}
