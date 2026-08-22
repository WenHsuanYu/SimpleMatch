#!/usr/bin/env bash

# Local registry image-lock policy and digest identity helpers.
# Publication belongs to publish-local-images.sh and manifest rendering belongs
# to render-local-kubernetes-manifest.sh. This module is side-effect free and
# owns the semantic contract between canonical local images and registry locks.

_simplematch_local_image_transport_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/lib/local-image-inventory.sh
source "$_simplematch_local_image_transport_dir/local-image-inventory.sh"
# shellcheck source=scripts/lib/local-registry.sh
source "$_simplematch_local_image_transport_dir/local-registry.sh"
unset _simplematch_local_image_transport_dir

# The transport selector was removed when the local registry became canonical.
# Reject an inherited legacy override instead of silently ignoring stale shell,
# CI, or developer configuration.
simplematch_local_image_transport_reject_legacy_override() {
  [[ -z "${SIMPLEMATCH_LOCAL_IMAGE_TRANSPORT:-}" ]] || {
    printf '%s\n' \
      'SIMPLEMATCH_LOCAL_IMAGE_TRANSPORT was removed; unset it because registry is the only supported local Kubernetes image transport.' >&2
    return 1
  }
}

simplematch_local_image_lock_validate_file() {
  local lock_file="$1"
  local line_number=0
  local line field_count
  local service source_image registry_tag digest_reference
  local source_repository source_tag expected_source_repository
  local registry_tag_repository registry_tag_tag registry_repository expected_registry_repository
  local entry image_class build_source repository
  local -A canonical_repositories=()
  local -A seen_services=()
  local -A seen_sources=()

  simplematch_local_image_inventory_validate || return 1
  while IFS='|' read -r image_class service build_source repository; do
    canonical_repositories["$service"]="$repository"
  done < <(simplematch_local_image_inventory_entries)

  [[ -s "$lock_file" ]] || {
    printf 'local image lock is missing or empty: %s\n' "$lock_file" >&2
    return 1
  }

  while IFS= read -r line || [[ -n "$line" ]]; do
    line_number=$((line_number + 1))
    field_count="$(awk -F'|' '{print NF}' <<<"$line")"
    [[ "$field_count" -eq 4 ]] || {
      printf 'local image lock line %d must contain exactly four fields: %s\n' "$line_number" "$lock_file" >&2
      return 1
    }

    IFS='|' read -r service source_image registry_tag digest_reference <<<"$line"
    [[ -n "$service" && -n "$source_image" && -n "$registry_tag" && -n "$digest_reference" ]] || {
      printf 'malformed local image lock line %d: %s\n' "$line_number" "$lock_file" >&2
      return 1
    }
    [[ -n "${canonical_repositories[$service]+x}" ]] || {
      printf 'unknown service in local image lock: %s\n' "$service" >&2
      return 1
    }
    [[ -z "${seen_services[$service]+x}" ]] || {
      printf 'duplicate service in local image lock: %s\n' "$service" >&2
      return 1
    }
    seen_services["$service"]=1

    source_repository="${source_image%:*}"
    source_tag="${source_image##*:}"
    [[ -n "$source_repository" && "$source_repository" != "$source_image" && -n "$source_tag" ]] || {
      printf 'source image must be tag qualified in local image lock: %s\n' "$source_image" >&2
      return 1
    }
    expected_source_repository="${canonical_repositories[$service]}"
    [[ "$source_repository" == "$expected_source_repository" ]] || {
      printf 'local image lock service/source mismatch for %s: expected %s, got %s\n' \
        "$service" "$expected_source_repository" "$source_repository" >&2
      return 1
    }
    [[ -z "${seen_sources[$source_repository]+x}" ]] || {
      printf 'duplicate source repository in local image lock: %s\n' "$source_repository" >&2
      return 1
    }
    seen_sources["$source_repository"]=1

    registry_tag_repository="${registry_tag%:*}"
    registry_tag_tag="${registry_tag##*:}"
    [[ -n "$registry_tag_repository" && "$registry_tag_repository" != "$registry_tag" && -n "$registry_tag_tag" ]] || {
      printf 'registry image must be tag qualified for service %s: %s\n' "$service" "$registry_tag" >&2
      return 1
    }
    [[ "$registry_tag_tag" == "$source_tag" ]] || {
      printf 'source/registry tag mismatch for service %s: %s vs %s\n' \
        "$service" "$source_tag" "$registry_tag_tag" >&2
      return 1
    }

    registry_repository="${digest_reference%@*}"
    [[ "$digest_reference" =~ @sha256:[0-9a-f]{64}$ && -n "$registry_repository" ]] || {
      printf 'local image lock entry is not digest pinned for service %s: %s\n' "$service" "$digest_reference" >&2
      return 1
    }
    [[ "$registry_tag_repository" == "$registry_repository" ]] || {
      printf 'registry tag/digest repository mismatch for service %s\n' "$service" >&2
      return 1
    }
    expected_registry_repository="$(simplematch_registry_endpoint)/${expected_source_repository}"
    [[ "$registry_repository" == "$expected_registry_repository" ]] || {
      printf 'registry repository is not the canonical local registry repository for service %s: expected %s, got %s\n' \
        "$service" "$expected_registry_repository" "$registry_repository" >&2
      return 1
    }
  done <"$lock_file"

  (( line_number > 0 )) || return 1
}

simplematch_local_image_lock_entry() {
  local lock_file="$1"
  local service="$2"
  local matches
  local entry
  local entry_service source_image registry_tag digest_reference

  simplematch_local_image_lock_validate_file "$lock_file" || return 1
  simplematch_local_image_inventory_has_service "$service" || {
    printf 'unknown local image service requested from lock: %s\n' "$service" >&2
    return 1
  }

  matches="$(awk -F'|' -v service="$service" '$1 == service { count += 1; line = $0 } END { if (count == 1) print line; else exit 1 }' "$lock_file")" || {
    printf 'local image lock must contain exactly one entry for service %s: %s\n' "$service" "$lock_file" >&2
    return 1
  }
  entry="$matches"
  IFS='|' read -r entry_service source_image registry_tag digest_reference <<<"$entry"
  [[ "$entry_service" == "$service" && -n "$source_image" && -n "$registry_tag" ]] || {
    printf 'malformed local image lock entry for service %s\n' "$service" >&2
    return 1
  }

  printf '%s\n' "$entry"
}

simplematch_local_image_lock_digest_reference() {
  local entry
  entry="$(simplematch_local_image_lock_entry "$1" "$2")" || return 1
  printf '%s\n' "${entry##*|}"
}

simplematch_local_image_lock_digest() {
  local digest_reference
  digest_reference="$(simplematch_local_image_lock_digest_reference "$1" "$2")" || return 1
  printf '%s\n' "${digest_reference##*@}"
}

# Rendering deliberately supports only deployment profiles that the repository
# owns end-to-end. A complete local overlay lock must contain every image used by
# deploy/k8s/overlays/local. The existing Matching fleet-only certification is
# the one supported partial profile. Any other partial lock is rejected instead
# of silently leaving mutable :local images in a registry-backed manifest.
simplematch_local_image_lock_render_profile() {
  local lock_file="$1"
  local line service
  local -a overlay_services=()
  local -A locked_services=()

  simplematch_local_image_lock_validate_file "$lock_file" || return 1
  while IFS= read -r line || [[ -n "$line" ]]; do
    service="${line%%|*}"
    locked_services["$service"]=1
  done <"$lock_file"

  if ((${#locked_services[@]} == 1)) && [[ -n "${locked_services[matching]+x}" ]]; then
    printf '%s\n' 'matching-only'
    return 0
  fi

  mapfile -t overlay_services < <(simplematch_local_image_inventory_local_overlay_services)
  for service in "${overlay_services[@]}"; do
    [[ -n "${locked_services[$service]+x}" ]] || {
      printf 'local image lock is not complete for local overlay; missing service: %s\n' "$service" >&2
      return 1
    }
  done

  printf '%s\n' 'full'
}

simplematch_local_image_lock_render_services() {
  local profile
  profile="$(simplematch_local_image_lock_render_profile "$1")" || return 1
  case "$profile" in
    matching-only)
      printf '%s\n' matching
      ;;
    full)
      simplematch_local_image_inventory_local_overlay_services
      ;;
  esac
}
