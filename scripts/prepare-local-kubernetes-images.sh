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

transport="${SIMPLEMATCH_LOCAL_IMAGE_TRANSPORT:-$SIMPLEMATCH_LOCAL_IMAGE_TRANSPORT_DEFAULT}"
image_tag="${SIMPLEMATCH_LOCAL_IMAGE_TAG:-local}"
cluster_name="${SIMPLEMATCH_KIND_CLUSTER_NAME:-simplematch-live}"
image_lock="${SIMPLEMATCH_LOCAL_IMAGE_LOCK:-$repo_root/out/local-images.lock}"
matching_only=false
SIMPLEMATCH_DRY_RUN=false

usage() {
  cat <<'EOF_USAGE'
Usage:
  scripts/prepare-local-kubernetes-images.sh [options]

Options:
  --transport MODE    registry (default) or kind-load.
  --tag TAG           Source image tag (default: local).
  --cluster NAME      kind cluster name (default: simplematch-live).
  --image-lock PATH   Registry image lockfile output path.
  --matching-only     Prepare only the Matching image.
  --dry-run           Print side effects without changing Docker/kind.
  -h, --help          Show this help.

registry:
  Verify the repository local registry is connected to the target kind cluster,
  publish selected canonical inventory images, and write immutable references.

kind-load:
  Compatibility fallback. Normalize local images when needed and import the
  selected canonical inventory directly into every kind node.
EOF_USAGE
}

while (($# > 0)); do
  case "$1" in
    --transport) transport="${2:?--transport requires a value}"; shift 2 ;;
    --tag) image_tag="${2:?--tag requires a value}"; shift 2 ;;
    --cluster) cluster_name="${2:?--cluster requires a value}"; shift 2 ;;
    --image-lock) image_lock="${2:?--image-lock requires a value}"; shift 2 ;;
    --matching-only) matching_only=true; shift ;;
    --dry-run) SIMPLEMATCH_DRY_RUN=true; shift ;;
    -h|--help) usage; exit 0 ;;
    *) usage >&2; simplematch_die "unknown option: $1" ;;
  esac
done

simplematch_local_image_transport_validate "$transport" || exit 2
simplematch_local_image_tag_validate "$image_tag" || exit 2
simplematch_local_image_inventory_validate || exit 2

case "$transport" in
  registry)
    if [[ "$SIMPLEMATCH_DRY_RUN" == true ]]; then
      printf 'DRY RUN: verify local registry integration for kind cluster %s\n' "$cluster_name"
    else
      simplematch_registry_verify "$cluster_name"
    fi

    publish_args=(--tag "$image_tag" --output "$image_lock")
    [[ "$matching_only" == true ]] && publish_args+=(--service matching)
    [[ "$SIMPLEMATCH_DRY_RUN" == true ]] && publish_args+=(--dry-run)
    bash "$script_dir/publish-local-images.sh" "${publish_args[@]}"
    ;;

  kind-load)
    if [[ "$SIMPLEMATCH_DRY_RUN" != true ]]; then
      simplematch_require_command docker
      simplematch_require_command kind
    fi

    if [[ "$matching_only" == true ]]; then
      local_images=("$(simplematch_local_image_inventory_source_image matching "$image_tag")")
      if [[ "$SIMPLEMATCH_DRY_RUN" == true ]]; then
        simplematch_quote_command docker image inspect "${local_images[0]}"
      else
        docker image inspect "${local_images[0]}" >/dev/null 2>&1 ||
          simplematch_die "local image does not exist: ${local_images[0]}"
      fi
    else
      if [[ "$SIMPLEMATCH_DRY_RUN" == true ]]; then
        simplematch_quote_command bash "$script_dir/normalize-local-images-for-kind.sh" --tag "$image_tag"
      else
        bash "$script_dir/normalize-local-images-for-kind.sh" --tag "$image_tag"
      fi
      mapfile -t local_images < <(simplematch_local_image_inventory_images "$image_tag")
      [[ ${#local_images[@]} -gt 0 ]] || simplematch_die 'local image inventory is empty'
    fi

    if [[ "$SIMPLEMATCH_DRY_RUN" == true ]]; then
      simplematch_quote_command kind load docker-image --name "$cluster_name" "${local_images[@]}"
    else
      kind load docker-image --name "$cluster_name" "${local_images[@]}"
    fi
    ;;
esac

simplematch_info "Prepared local Kubernetes images with transport=$transport"
