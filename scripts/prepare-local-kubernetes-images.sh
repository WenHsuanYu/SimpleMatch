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
  --transport MODE    Compatibility option; only registry is accepted.
  --tag TAG           Source image tag (default: local).
  --cluster NAME      kind cluster name (default: simplematch-live).
  --image-lock PATH   Registry image lockfile output path.
  --matching-only     Prepare only the Matching image.
  --dry-run           Print side effects without changing Docker/kind.
  -h, --help          Show this help.

The repository-owned local registry is the only supported Kubernetes image
transport. Selected canonical images are published and recorded by immutable
digest reference in the image lockfile.
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

if [[ "$SIMPLEMATCH_DRY_RUN" == true ]]; then
  printf 'DRY RUN: verify local registry integration for kind cluster %s\n' "$cluster_name"
else
  simplematch_registry_verify "$cluster_name"
fi

publish_args=(--tag "$image_tag" --output "$image_lock")
[[ "$matching_only" == true ]] && publish_args+=(--service matching)
[[ "$SIMPLEMATCH_DRY_RUN" == true ]] && publish_args+=(--dry-run)
bash "$script_dir/publish-local-images.sh" "${publish_args[@]}"

simplematch_info 'Prepared local Kubernetes images through the repository local registry'
