#!/usr/bin/env bash

set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd -- "$script_dir/.." && pwd)"
image_tag="${SIMPLEMATCH_LOCAL_IMAGE_TAG:-local}"

usage() {
  cat <<'EOF'
Usage:
  scripts/normalize-local-images-for-kind.sh [--tag TAG]

Create kind-transfer copies for Spring Boot images built by bootBuildImage.
The source bootBuildImage image remains available with a `-boot` tag; the
requested local tag is replaced by a flattened disposable transfer image.
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --tag)
      image_tag="${2:?--tag requires a value}"
      shift 2
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    *)
      usage >&2
      printf 'Unknown option: %s\n' "$1" >&2
      exit 2
      ;;
  esac
done

command -v docker >/dev/null 2>&1 || {
  printf '%s\n' 'Docker is required to normalize local images for kind.' >&2
  exit 1
}

normalizer="$repo_root/deploy/docker/Dockerfile.kind-normalized"
[[ -f "$normalizer" ]] || {
  printf 'Kind image normalizer does not exist: %s\n' "$normalizer" >&2
  exit 1
}

# Docker Desktop may expose the default Buildx activity directory as read-only while its
# background builder owns it. Keep this disposable normalization build independent from that
# mutable Desktop state; the image content and active Docker context remain unchanged.
temporary_buildx_config=""
if [[ -z "${BUILDX_CONFIG:-}" ]]; then
  temporary_buildx_config="$(mktemp -d /tmp/simplematch-buildx-config.XXXXXX)"
  export BUILDX_CONFIG="$temporary_buildx_config"
  cleanup_buildx_config() {
    rm -rf "$temporary_buildx_config"
  }
  trap cleanup_buildx_config EXIT
fi

mapfile -t spring_images < <(
  bash "$repo_root/scripts/build-local-images.sh" --tag "$image_tag" --list |
    awk -F'|' '$1 == "spring" { print $4 }'
)

for image in "${spring_images[@]}"; do
  image_repository="${image%:*}"
  boot_image="${image_repository}:${image_tag}-boot"

  docker image inspect "$image" >/dev/null 2>&1 || {
    printf 'Local Spring image does not exist: %s\n' "$image" >&2
    exit 1
  }

  if ! docker image inspect "$boot_image" >/dev/null 2>&1; then
    docker tag "$image" "$boot_image"
  fi

  docker build \
    --file "$normalizer" \
    --build-arg "SOURCE_IMAGE=$boot_image" \
    --tag "$image" \
    "$repo_root"
  printf 'Normalized %s for kind; bootBuildImage source retained as %s.\n' \
    "$image" "$boot_image"
done
