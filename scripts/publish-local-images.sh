#!/usr/bin/env bash
set -Eeuo pipefail
IFS=$'\n\t'

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd -- "$script_dir/.." && pwd)"
# shellcheck source=scripts/lib/local-common.sh
source "$script_dir/lib/local-common.sh"
# shellcheck source=scripts/lib/local-registry.sh
source "$script_dir/lib/local-registry.sh"
# shellcheck source=scripts/lib/local-image-inventory.sh
source "$script_dir/lib/local-image-inventory.sh"
# shellcheck source=scripts/lib/local-image-transport.sh
source "$script_dir/lib/local-image-transport.sh"

image_tag="${SIMPLEMATCH_LOCAL_IMAGE_TAG:-local}"
output_file="${SIMPLEMATCH_LOCAL_IMAGE_LOCK:-$repo_root/out/local-images.lock}"
SIMPLEMATCH_DRY_RUN=false
selected_services=()
transient_registry_tags=()
temp_lock=""

cleanup() {
  local registry_tag
  [[ -n "$temp_lock" ]] && rm -f -- "$temp_lock"
  if [[ "$SIMPLEMATCH_DRY_RUN" != true ]]; then
    for registry_tag in "${transient_registry_tags[@]:-}"; do
      [[ -n "$registry_tag" ]] || continue
      docker image rm "$registry_tag" >/dev/null 2>&1 || true
    done
  fi
}
trap cleanup EXIT

usage() {
  cat <<'EOF_USAGE'
Usage:
  scripts/publish-local-images.sh [options]

Options:
  --tag TAG          Source image tag (default: local).
  --service NAME     Publish one canonical inventory service; may repeat.
  --output PATH      Write the image lockfile here.
  --dry-run          Print tag/push operations without changing Docker/registry.
  -h, --help         Show this help.

The lockfile format is pipe-delimited:
  service|source-image|registry-tag|registry-digest-reference

The registry tag is a transient host-side transport tag. Deployments consume the
immutable registry-digest-reference. Selected canonical inventory images are
pushed, the complete lockfile is validated and atomically installed, then the
temporary host registry tags are removed; registry content remains addressable
by digest.
EOF_USAGE
}

while (($# > 0)); do
  case "$1" in
    --tag)
      image_tag="${2:?--tag requires a value}"
      shift 2
      ;;
    --service)
      selected_services+=("${2:?--service requires a value}")
      shift 2
      ;;
    --output)
      output_file="${2:?--output requires a value}"
      shift 2
      ;;
    --dry-run)
      SIMPLEMATCH_DRY_RUN=true
      shift
      ;;
    -h|--help)
      usage
      trap - EXIT
      exit 0
      ;;
    *)
      usage >&2
      simplematch_die "unknown option: $1"
      ;;
  esac
done

simplematch_local_image_tag_validate "$image_tag" || exit 2
simplematch_local_image_inventory_validate || exit 2
simplematch_local_image_inventory_validate_selection "${selected_services[@]}" || exit 2

if [[ "$SIMPLEMATCH_DRY_RUN" != true ]]; then
  simplematch_require_command docker
  simplematch_registry_verify
  mkdir -p "$(dirname -- "$output_file")"
  temp_lock="$(mktemp "${output_file}.tmp.XXXXXX")"
else
  printf 'DRY RUN: image lockfile would be written atomically to %s\n' "$output_file"
fi

registry_endpoint="$(simplematch_registry_endpoint)"
published_count=0

while IFS='|' read -r _ service _ repository; do
  simplematch_local_image_inventory_service_selected "$service" "${selected_services[@]}" || continue

  source_image="${repository}:${image_tag}"
  registry_tag="${registry_endpoint}/${repository}:${image_tag}"
  published_count=$((published_count + 1))

  if [[ "$SIMPLEMATCH_DRY_RUN" == true ]]; then
    simplematch_quote_command docker image inspect "$source_image"
    simplematch_quote_command docker tag "$source_image" "$registry_tag"
    simplematch_quote_command docker push "$registry_tag"
    simplematch_quote_command docker image rm "$registry_tag"
    continue
  fi

  docker image inspect "$source_image" >/dev/null 2>&1 ||
    simplematch_die "local image does not exist: $source_image"

  simplematch_log "Publish $service"
  docker tag "$source_image" "$registry_tag"
  transient_registry_tags+=("$registry_tag")
  push_output="$(docker push "$registry_tag")"
  printf '%s\n' "$push_output"
  digest="$(sed -nE 's/.*digest: (sha256:[0-9a-f]{64}).*/\1/p' <<<"$push_output" | tail -1)"
  [[ "$digest" =~ ^sha256:[0-9a-f]{64}$ ]] ||
    simplematch_die "registry push did not report an OCI digest for $registry_tag"

  digest_reference="${registry_endpoint}/${repository}@${digest}"
  printf '%s|%s|%s|%s\n' \
    "$service" "$source_image" "$registry_tag" "$digest_reference" >>"$temp_lock"
done < <(simplematch_local_image_inventory_entries)

((published_count > 0)) || simplematch_die 'no local images selected for publication'

if [[ "$SIMPLEMATCH_DRY_RUN" != true ]]; then
  simplematch_local_image_lock_validate_file "$temp_lock" ||
    simplematch_die 'published image lockfile failed validation'
  mv -f -- "$temp_lock" "$output_file"
  temp_lock=""
  simplematch_info "Wrote local image lockfile with immutable references: $output_file"
fi

cleanup
trap - EXIT
