#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/lib/local-docker-storage.sh
source "$script_dir/lib/local-docker-storage.sh"

fail() {
  printf 'Docker storage preflight contract failed: %s\n' "$*" >&2
  exit 1
}

gib=$((1024 * 1024 * 1024))
filesystem_bytes=$((228 * gib))

simplematch_docker_storage_capacity_check \
  "$filesystem_bytes" "$((80 * gib))" "$((168 * gib))" ||
  fail 'safe capacity was rejected'

if simplematch_docker_storage_capacity_check \
    "$filesystem_bytes" "$((80 * gib))" "$((224 * gib))" 2>/dev/null; then
  fail 'a virtual-disk limit that can consume the host filesystem was accepted'
fi

if simplematch_docker_storage_capacity_check \
    "$filesystem_bytes" "$((39 * gib))" "$((168 * gib))" 2>/dev/null; then
  fail 'insufficient current host headroom was accepted'
fi

if simplematch_docker_storage_capacity_check invalid "$((80 * gib))" "$((168 * gib))"; then
  fail 'malformed capacity evidence was accepted'
fi

printf '%s\n' 'Docker storage preflight contract passed.'
