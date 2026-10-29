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
registry_endpoint="$(simplematch_registry_endpoint)"
printf 'matching|simplematch-matching:local|%s/simplematch-matching:local|%s/simplematch-matching@%s\n' \
  "$registry_endpoint" "$registry_endpoint" "$digest" >"$lock_file"

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

[[ "$SIMPLEMATCH_LOCAL_IMAGE_TRANSPORT_DEFAULT" == registry ]]
simplematch_local_image_transport_validate registry
simplematch_local_image_transport_validate kind-load
if simplematch_local_image_transport_validate invalid >/dev/null 2>&1; then
  printf '%s\n' 'invalid image transport unexpectedly accepted' >&2
  exit 1
fi

simplematch_local_image_lock_validate_file "$lock_file"
[[ "$(simplematch_local_image_lock_digest "$lock_file" matching)" == "$digest" ]]
[[ "$(simplematch_local_image_lock_digest_reference "$lock_file" matching)" == "${registry_endpoint}/simplematch-matching@${digest}" ]]
[[ "$(simplematch_local_image_transport_matching_reference registry local "$lock_file")" == "${registry_endpoint}/simplematch-matching@${digest}" ]]
[[ "$(simplematch_local_image_transport_matching_digest registry local "$lock_file")" == "$digest" ]]
[[ "$(simplematch_local_image_transport_matching_reference kind-load dev "$lock_file")" == 'simplematch-matching:dev' ]]

cat "$lock_file" "$lock_file" >"$duplicate_file"
if simplematch_local_image_lock_validate_file "$duplicate_file" >/dev/null 2>&1; then
  printf '%s\n' 'duplicate service/source lock entries unexpectedly accepted' >&2
  exit 1
fi

printf 'matching|simplematch-matching:local|%s/simplematch-matching:local|%s/simplematch-matching@sha256:bad\n' \
  "$registry_endpoint" "$registry_endpoint" >"$malformed_file"
if simplematch_local_image_lock_validate_file "$malformed_file" >/dev/null 2>&1; then
  printf '%s\n' 'malformed digest lock entry unexpectedly accepted' >&2
  exit 1
fi

printf 'matching|simplematch-matching:local|%s/simplematch-matching:local|%s/simplematch-matching@%s|\n' \
  "$registry_endpoint" "$registry_endpoint" "$digest" >"$malformed_file"
if simplematch_local_image_lock_validate_file "$malformed_file" >/dev/null 2>&1; then
  printf '%s\n' 'lock entry with a trailing fifth field unexpectedly accepted' >&2
  exit 1
fi

printf 'matching|simplematch-matching:local|%s/other:local|%s/simplematch-matching@%s\n' \
  "$registry_endpoint" "$registry_endpoint" "$digest" >"$malformed_file"
if simplematch_local_image_lock_validate_file "$malformed_file" >/dev/null 2>&1; then
  printf '%s\n' 'registry tag/digest repository mismatch unexpectedly accepted' >&2
  exit 1
fi

printf 'matching|simplematch/risk-service:local|%s/simplematch/risk-service:local|%s/simplematch/risk-service@%s\n' \
  "$registry_endpoint" "$registry_endpoint" "$digest" >"$malformed_file"
if simplematch_local_image_lock_validate_file "$malformed_file" >/dev/null 2>&1; then
  printf '%s\n' 'service/source repository mismatch unexpectedly accepted' >&2
  exit 1
fi

printf 'matching|simplematch-matching:local|%s/simplematch-matching:dev|%s/simplematch-matching@%s\n' \
  "$registry_endpoint" "$registry_endpoint" "$digest" >"$malformed_file"
if simplematch_local_image_lock_validate_file "$malformed_file" >/dev/null 2>&1; then
  printf '%s\n' 'source/registry tag mismatch unexpectedly accepted' >&2
  exit 1
fi

printf 'matching|simplematch-matching:local|%s/other:local|%s/other@%s\n' \
  "$registry_endpoint" "$registry_endpoint" "$digest" >"$malformed_file"
if simplematch_local_image_lock_validate_file "$malformed_file" >/dev/null 2>&1; then
  printf '%s\n' 'registry repository that loses canonical source identity unexpectedly accepted' >&2
  exit 1
fi

printf 'matching|simplematch-matching:local|remote.example/simplematch-matching:local|remote.example/simplematch-matching@%s\n' "$digest" >"$malformed_file"
if simplematch_local_image_lock_validate_file "$malformed_file" >/dev/null 2>&1; then
  printf '%s\n' 'registry repository outside the configured local registry unexpectedly accepted' >&2
  exit 1
fi

# Step 3/4 contract: registry is the default and never executes the legacy kind
# normalization/import path.
bash "$script_dir/prepare-local-kubernetes-images.sh" \
  --tag local \
  --cluster simplematch-live \
  --image-lock "$lock_file" \
  --matching-only \
  --dry-run >"$dry_run_file"
grep -Fq "docker push ${registry_endpoint}/simplematch-matching:local" "$dry_run_file"
grep -Fq "docker image rm ${registry_endpoint}/simplematch-matching:local" "$dry_run_file"
if grep -Fq 'kind load docker-image' "$dry_run_file" || grep -Fq 'normalize-local-images-for-kind.sh' "$dry_run_file"; then
  printf '%s\n' 'default registry preparation unexpectedly executes the kind-load fallback' >&2
  exit 1
fi

# The explicit selector must produce the same registry behavior.
bash "$script_dir/prepare-local-kubernetes-images.sh" \
  --transport registry --tag local --cluster simplematch-live --image-lock "$lock_file" \
  --matching-only --dry-run >"$dry_run_file"
grep -Fq "docker push ${registry_endpoint}/simplematch-matching:local" "$dry_run_file"

# Step 3/4 contract: Matching-only fallback imports exactly the local Matching
# image and does not need Spring image normalization.
bash "$script_dir/prepare-local-kubernetes-images.sh" \
  --transport kind-load --tag local --cluster simplematch-live --matching-only --dry-run >"$dry_run_file"
grep -Fq 'docker image inspect simplematch-matching:local' "$dry_run_file"
grep -Fq 'kind load docker-image --name simplematch-live simplematch-matching:local' "$dry_run_file"
if grep -Fq 'normalize-local-images-for-kind.sh' "$dry_run_file"; then
  printf '%s\n' 'Matching-only kind-load fallback unexpectedly normalizes Spring images' >&2
  exit 1
fi

# Full fallback retains the legacy normalizer required by the accepted sequence.
bash "$script_dir/prepare-local-kubernetes-images.sh" \
  --transport kind-load --tag local --cluster simplematch-live --dry-run >"$dry_run_file"
grep -Fq 'normalize-local-images-for-kind.sh --tag local' "$dry_run_file"
grep -Fq 'kind load docker-image --name simplematch-live' "$dry_run_file"

# Environment selection remains equivalent to the CLI selector.
SIMPLEMATCH_LOCAL_IMAGE_TRANSPORT=kind-load \
  bash "$script_dir/prepare-local-kubernetes-images.sh" --tag local --cluster simplematch-live \
    --matching-only --dry-run >"$dry_run_file"
grep -Fq 'kind load docker-image --name simplematch-live simplematch-matching:local' "$dry_run_file"

boot_override_dry_run="$(SIMPLEMATCH_BOOT_RUN_IMAGE=local/paketo-run:amd64 \
  "$script_dir/build-local-images.sh" --dry-run --service account-service)"
grep -Fq -- '--runImage=local/paketo-run:amd64' <<<"$boot_override_dry_run"
grep -Fq -- '--pullPolicy=IF_NOT_PRESENT' <<<"$boot_override_dry_run"

printf '%s\n' 'Local image inventory, registry publication, and staged dual-transport contract passed.'

# Validate the public publisher boundary before any external command.
if SIMPLEMATCH_LOCAL_REGISTRY_HOST=registry.example.com \
    bash "$script_dir/publish-local-images.sh" --service matching --dry-run >/dev/null 2>&1; then
  printf 'Publisher accepted a remote registry host.\n' >&2; exit 1
fi
for port in 0 65536 not-a-port; do
  if SIMPLEMATCH_LOCAL_REGISTRY_PORT="$port" \
      bash "$script_dir/publish-local-images.sh" --service matching --dry-run >/dev/null 2>&1; then
    printf 'Publisher accepted an invalid port: %s\n' "$port" >&2; exit 1
  fi
done

# A failed push or missing digest must preserve the previous valid lock.
publisher_fixture="$(mktemp -d)"
trap 'rm -rf -- "$publisher_fixture"; rm -f "$lock_file" "$duplicate_file" "$malformed_file" "$dry_run_file" "$inventory_expected" "$inventory_actual"' EXIT
mkdir "$publisher_fixture/bin"
cat >"$publisher_fixture/bin/docker" <<'EOF_DOCKER'
#!/usr/bin/env bash
set -euo pipefail
case "$*" in
  *'{{.State.Running}}'*) printf '%s\n' true ;;
  *'{{.Config.Image}}'*) printf '%s\n' registry:3 ;;
  *'{{.HostConfig.RestartPolicy.Name}}'*) printf '%s\n' unless-stopped ;;
  *'.Mounts'*) printf '%s\n' simplematch-local-registry-data ;;
  'port simplematch-local-registry 5000/tcp') printf '%s\n' "127.0.0.1:${SIMPLEMATCH_LOCAL_REGISTRY_PORT:-5001}" ;;
  'inspect simplematch-local-registry'|'volume inspect '*|'image inspect '*|'tag '*|'image rm '*) ;;
  'push '*)
    printf '%s\n' push >>"${PUBLISHER_CALLS:?}"
    [[ "${PUBLISHER_FAILURE:?}" != push ]] || exit 42
    printf '%s\n' 'push completed without digest'
    ;;
  *) printf 'Unexpected Docker call: %s\n' "$*" >&2; exit 99 ;;
esac
EOF_DOCKER
chmod 755 "$publisher_fixture/bin/docker"
for failure in push digest; do
  if PATH="$publisher_fixture/bin:$PATH" PUBLISHER_FAILURE="$failure" \
      PUBLISHER_CALLS="$publisher_fixture/calls" \
      bash "$script_dir/publish-local-images.sh" --service matching --output "$lock_file" \
      >"$publisher_fixture/output" 2>&1; then
    printf 'Publisher accepted %s failure.\n' "$failure" >&2; exit 1
  fi
  [[ -f "$publisher_fixture/calls" ]] || { cat "$publisher_fixture/output" >&2; exit 1; }
  [[ "$(simplematch_local_image_lock_digest "$lock_file" matching)" == "$digest" ]]
  [[ "$(wc -l <"$lock_file")" == 1 ]]
  rm "$publisher_fixture/calls"
done
printf '%s\n' 'Publisher failures preserve the previous image lock.'
