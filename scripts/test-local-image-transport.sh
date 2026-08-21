#!/usr/bin/env bash
set -Eeuo pipefail
IFS=$'\n\t'
trap 'printf "Local image transport contract failed at line %s\n" "$LINENO" >&2' ERR

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/lib/local-image-inventory.sh
source "$script_dir/lib/local-image-inventory.sh"
# shellcheck source=scripts/lib/local-image-transport.sh
source "$script_dir/lib/local-image-transport.sh"

lock_file="$(mktemp "${TMPDIR:-/tmp}/simplematch-image-lock-test.XXXXXX")"
duplicate_file="$(mktemp "${TMPDIR:-/tmp}/simplematch-image-lock-duplicate-test.XXXXXX")"
malformed_file="$(mktemp "${TMPDIR:-/tmp}/simplematch-image-lock-malformed-test.XXXXXX")"
dry_run_file="$(mktemp "${TMPDIR:-/tmp}/simplematch-image-transport-dry-run.XXXXXX")"
inventory_expected="$(mktemp "${TMPDIR:-/tmp}/simplematch-image-inventory-expected.XXXXXX")"
inventory_actual="$(mktemp "${TMPDIR:-/tmp}/simplematch-image-inventory-actual.XXXXXX")"
trap 'rm -f "$lock_file" "$duplicate_file" "$malformed_file" "$dry_run_file" "$inventory_expected" "$inventory_actual"' EXIT

digest="sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
printf 'matching|simplematch-matching:local|localhost:5001/simplematch-matching:local|localhost:5001/simplematch-matching@%s\n' "$digest" >"$lock_file"

# Canonical inventory is a side-effect-free dependency shared by build,
# publication, rendering, and the legacy kind-load transport.
simplematch_local_image_inventory_validate
simplematch_local_image_tag_validate local
simplematch_local_image_inventory_emit local >"$inventory_expected"
bash "$script_dir/build-local-images.sh" --tag local --list >"$inventory_actual"
cmp -s "$inventory_expected" "$inventory_actual" || {
  printf '%s\n' 'build --list drifted from the canonical local image inventory' >&2
  exit 1
}
[[ "$(simplematch_local_image_inventory_source_image matching local)" == 'simplematch-matching:local' ]]
[[ "$(simplematch_local_image_inventory_repository matching)" == 'simplematch-matching' ]]
[[ "$(simplematch_local_image_inventory_images local | wc -l)" -eq "${#SIMPLEMATCH_LOCAL_IMAGE_INVENTORY[@]}" ]]
if simplematch_local_image_tag_validate 'bad/tag' >/dev/null 2>&1; then
  printf '%s\n' 'invalid local image tag unexpectedly accepted' >&2
  exit 1
fi
if (
  SIMPLEMATCH_LOCAL_IMAGE_INVENTORY+=('native|matching|deploy/docker/Dockerfile.other|simplematch/other')
  simplematch_local_image_inventory_validate >/dev/null 2>&1
); then
  printf '%s\n' 'duplicate canonical inventory service unexpectedly accepted' >&2
  exit 1
fi
if (
  SIMPLEMATCH_LOCAL_IMAGE_INVENTORY+=('native|extra|deploy/docker/Dockerfile.extra|simplematch/extra|unexpected')
  simplematch_local_image_inventory_validate >/dev/null 2>&1
); then
  printf '%s\n' 'five-field canonical inventory entry unexpectedly accepted' >&2
  exit 1
fi
if bash "$script_dir/publish-local-images.sh" --tag local --service does-not-exist --dry-run >/dev/null 2>&1; then
  printf '%s\n' 'unknown publisher service selector unexpectedly accepted' >&2
  exit 1
fi
if bash "$script_dir/publish-local-images.sh" --tag local --service matching --service matching --dry-run >/dev/null 2>&1; then
  printf '%s\n' 'duplicate publisher service selector unexpectedly accepted' >&2
  exit 1
fi

simplematch_local_image_transport_validate registry
simplematch_local_image_transport_validate kind-load
if simplematch_local_image_transport_validate invalid >/dev/null 2>&1; then
  printf '%s\n' 'invalid image transport unexpectedly accepted' >&2
  exit 1
fi

simplematch_local_image_lock_validate_file "$lock_file"
[[ "$(simplematch_local_image_lock_digest "$lock_file" matching)" == "$digest" ]]
[[ "$(simplematch_local_image_lock_digest_reference "$lock_file" matching)" == "localhost:5001/simplematch-matching@${digest}" ]]
[[ "$(simplematch_local_image_transport_matching_reference registry local "$lock_file")" == "localhost:5001/simplematch-matching@${digest}" ]]
[[ "$(simplematch_local_image_transport_matching_digest registry local "$lock_file")" == "$digest" ]]
[[ "$(simplematch_local_image_transport_matching_reference kind-load dev "$lock_file")" == 'simplematch-matching:dev' ]]

cat "$lock_file" "$lock_file" >"$duplicate_file"
if simplematch_local_image_lock_validate_file "$duplicate_file" >/dev/null 2>&1; then
  printf '%s\n' 'duplicate service/source lock entries unexpectedly accepted' >&2
  exit 1
fi

printf '%s\n' 'matching|simplematch-matching:local|localhost:5001/simplematch-matching:local|localhost:5001/simplematch-matching@sha256:bad' >"$malformed_file"
if simplematch_local_image_lock_validate_file "$malformed_file" >/dev/null 2>&1; then
  printf '%s\n' 'malformed digest lock entry unexpectedly accepted' >&2
  exit 1
fi

printf 'matching|simplematch-matching:local|localhost:5001/simplematch-matching:local|localhost:5001/simplematch-matching@%s|\n' "$digest" >"$malformed_file"
if simplematch_local_image_lock_validate_file "$malformed_file" >/dev/null 2>&1; then
  printf '%s\n' 'lock entry with a trailing fifth field unexpectedly accepted' >&2
  exit 1
fi

printf 'matching|simplematch-matching:local|localhost:5001/other:local|localhost:5001/simplematch-matching@%s\n' "$digest" >"$malformed_file"
if simplematch_local_image_lock_validate_file "$malformed_file" >/dev/null 2>&1; then
  printf '%s\n' 'registry tag/digest repository mismatch unexpectedly accepted' >&2
  exit 1
fi

printf 'matching|simplematch/risk-service:local|localhost:5001/simplematch/risk-service:local|localhost:5001/simplematch/risk-service@%s\n' "$digest" >"$malformed_file"
if simplematch_local_image_lock_validate_file "$malformed_file" >/dev/null 2>&1; then
  printf '%s\n' 'service/source repository mismatch unexpectedly accepted' >&2
  exit 1
fi

printf 'matching|simplematch-matching:local|localhost:5001/simplematch-matching:dev|localhost:5001/simplematch-matching@%s\n' "$digest" >"$malformed_file"
if simplematch_local_image_lock_validate_file "$malformed_file" >/dev/null 2>&1; then
  printf '%s\n' 'source/registry tag mismatch unexpectedly accepted' >&2
  exit 1
fi

printf 'matching|simplematch-matching:local|localhost:5001/other:local|localhost:5001/other@%s\n' "$digest" >"$malformed_file"
if simplematch_local_image_lock_validate_file "$malformed_file" >/dev/null 2>&1; then
  printf '%s\n' 'registry repository that loses canonical source identity unexpectedly accepted' >&2
  exit 1
fi

bash "$script_dir/prepare-local-kubernetes-images.sh" \
  --transport registry \
  --tag local \
  --cluster simplematch-live \
  --image-lock "$lock_file" \
  --matching-only \
  --dry-run >"$dry_run_file"
grep -Fq 'docker push localhost:5001/simplematch-matching:local' "$dry_run_file"
grep -Fq 'docker image rm localhost:5001/simplematch-matching:local' "$dry_run_file"
if grep -Fq 'kind load docker-image' "$dry_run_file"; then
  printf '%s\n' 'registry dry-run unexpectedly uses kind load' >&2
  exit 1
fi

bash "$script_dir/prepare-local-kubernetes-images.sh" \
  --transport kind-load \
  --tag local \
  --cluster simplematch-live \
  --matching-only \
  --dry-run >"$dry_run_file"
grep -Fq 'docker image inspect simplematch-matching:local' "$dry_run_file"
grep -Fq 'kind load docker-image --name simplematch-live simplematch-matching:local' "$dry_run_file"

printf '%s\n' 'Local image inventory and transport contract passed.'
