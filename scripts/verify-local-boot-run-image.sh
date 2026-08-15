#!/usr/bin/env bash

set -euo pipefail

image_reference="${1:?usage: verify-local-boot-run-image.sh IMAGE [PLATFORM]}"
platform="${2:-linux/amd64}"
expected_os="${platform%%/*}"
expected_arch="${platform##*/}"
temporary_archive="$(mktemp /tmp/simplematch-run-image.XXXXXX.tar)"

cleanup() {
  rm -f "$temporary_archive"
}
trap cleanup EXIT

[[ "$expected_os" == linux && "$expected_arch" == amd64 ]] || {
  printf 'Only linux/amd64 local BootBuildImage run images are supported: %s\n' "$platform" >&2
  exit 2
}

host_arch="$(uname -m)"
case "$host_arch" in
  x86_64|amd64) ;;
  *)
    printf 'Local BootBuildImage requires an amd64 host; found %s\n' "$host_arch" >&2
    exit 2
    ;;
esac

image_metadata="$(docker image inspect "$image_reference" \
  --format '{{.Os}} {{.Architecture}}' 2>/dev/null)" || {
  printf 'Run image does not exist locally: %s\n' "$image_reference" >&2
  exit 1
}
read -r image_os image_arch <<<"$image_metadata"
[[ "$image_os" == "$expected_os" && "$image_arch" == "$expected_arch" ]] || {
  printf 'Run image %s is %s/%s, expected %s/%s\n' \
    "$image_reference" "$image_os" "$image_arch" "$expected_os" "$expected_arch" >&2
  exit 1
}

docker image save --platform "$platform" "$image_reference" -o "$temporary_archive" >/dev/null
[[ -s "$temporary_archive" ]] || {
  printf 'Run image %s could not be exported for %s\n' "$image_reference" "$platform" >&2
  exit 1
}

printf 'Local BootBuildImage run image is usable: %s (%s/%s)\n' \
  "$image_reference" "$image_os" "$image_arch"
