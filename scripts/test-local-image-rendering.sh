#!/usr/bin/env bash
set -Eeuo pipefail
IFS=$'\n\t'
trap 'printf "Local image rendering contract failed at line %s\n" "$LINENO" >&2' ERR

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd -- "$script_dir/.." && pwd)"
# shellcheck source=scripts/lib/local-image-inventory.sh
source "$script_dir/lib/local-image-inventory.sh"
# shellcheck source=scripts/lib/local-image-transport.sh
source "$script_dir/lib/local-image-transport.sh"

renderer="$script_dir/render-local-kubernetes-manifest.sh"
temp_dir="$(mktemp -d "${TMPDIR:-/tmp}/simplematch-image-rendering-test.XXXXXX")"
trap 'rm -rf "$temp_dir"' EXIT

full_lock="$temp_dir/full.lock"
matching_lock="$temp_dir/matching.lock"
partial_lock="$temp_dir/partial.lock"
full_manifest="$temp_dir/full.yaml"
matching_manifest="$temp_dir/matching.yaml"
kind_load_manifest="$temp_dir/kind-load.yaml"
atomic_target="$temp_dir/atomic.yaml"
overlay_expected="$temp_dir/overlay-expected.txt"
overlay_actual="$temp_dir/overlay-actual.txt"
fake_bin="$temp_dir/fake-bin"
digest='sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef'
namespace='simplematch-render-contract'

command -v kubectl >/dev/null 2>&1 || {
  printf '%s\n' 'kubectl is required for local image rendering contract' >&2
  exit 1
}

append_lock_entry() {
  local service="$1"
  local file="$2"
  local repository
  repository="$(simplematch_local_image_inventory_repository "$service")"
  printf '%s|%s:local|localhost:5001/%s:local|localhost:5001/%s@%s\n' \
    "$service" "$repository" "$repository" "$repository" "$digest" >>"$file"
}

# Keep the canonical rendering scope synchronized with the tracked local
# Kustomization. A new or removed `images:` entry must be reflected explicitly in
# the inventory instead of silently escaping digest rendering.
while IFS= read -r service; do
  simplematch_local_image_inventory_repository "$service"
done < <(simplematch_local_image_inventory_local_overlay_services) | sort -u >"$overlay_expected"
awk '
  /^images:[[:space:]]*$/ { in_images=1; next }
  in_images && /^[^[:space:]]/ { exit }
  in_images && /^[[:space:]]+-[[:space:]]+name:[[:space:]]+/ { print $3 }
' "$repo_root/deploy/k8s/overlays/local/kustomization.yaml" | sort -u >"$overlay_actual"
cmp -s "$overlay_expected" "$overlay_actual" || {
  printf '%s\n' 'canonical local image inventory drifted from deploy/k8s/overlays/local images' >&2
  diff -u "$overlay_expected" "$overlay_actual" >&2 || true
  exit 1
}

# A normal publication lock contains the complete canonical inventory, including
# verification-only images. Rendering intentionally consumes only the services
# owned by deploy/k8s/overlays/local.
while IFS='|' read -r _ service _ _; do
  append_lock_entry "$service" "$full_lock"
done < <(simplematch_local_image_inventory_entries)
append_lock_entry matching "$matching_lock"
append_lock_entry risk-service "$partial_lock"

[[ "$(simplematch_local_image_lock_render_profile "$full_lock")" == full ]]
[[ "$(simplematch_local_image_lock_render_profile "$matching_lock")" == matching-only ]]
if simplematch_local_image_lock_render_profile "$partial_lock" >/dev/null 2>&1; then
  printf '%s\n' 'unsupported partial image lock unexpectedly accepted for rendering' >&2
  exit 1
fi

bash "$renderer" \
  --transport registry \
  --image-lock "$full_lock" \
  --namespace "$namespace" \
  --output "$full_manifest" >/dev/null

grep -Fq "namespace: ${namespace}" "$full_manifest"
while IFS= read -r service; do
  repository="$(simplematch_local_image_inventory_repository "$service")"
  digest_reference="localhost:5001/${repository}@${digest}"
  grep -Fq "image: ${digest_reference}" "$full_manifest" || {
    printf 'full registry render did not digest-pin %s\n' "$service" >&2
    exit 1
  }
  if grep -Fq "image: ${repository}:" "$full_manifest" ||
     grep -Fq "image: ${repository}@" "$full_manifest"; then
    printf 'full registry render left mutable/local repository for %s\n' "$service" >&2
    exit 1
  fi
done < <(simplematch_local_image_inventory_local_overlay_services)
if grep -Eq '^[[:space:]]*image:[[:space:]]+[^[:space:]]+:local[[:space:]]*$' "$full_manifest"; then
  printf '%s\n' 'full registry render left an untracked mutable :local image' >&2
  exit 1
fi

bash "$renderer" \
  --transport registry \
  --image-lock "$matching_lock" \
  --namespace "$namespace" \
  --output "$matching_manifest" >/dev/null
matching_repository="$(simplematch_local_image_inventory_repository matching)"
grep -Fq "image: localhost:5001/${matching_repository}@${digest}" "$matching_manifest"
if grep -Fq "image: ${matching_repository}:" "$matching_manifest"; then
  printf '%s\n' 'matching-only registry render left mutable Matching image' >&2
  exit 1
fi
# Matching-only is an explicit partial deployment profile. Unselected workload
# images remain local because the certification later selects only Matching.
grep -Fq 'image: simplematch/risk-service:local' "$matching_manifest"

if bash "$renderer" \
    --transport registry \
    --image-lock "$partial_lock" \
    --namespace "$namespace" \
    --output "$temp_dir/unsupported.yaml" >/dev/null 2>&1; then
  printf '%s\n' 'renderer unexpectedly accepted unsupported partial image lock' >&2
  exit 1
fi

bash "$renderer" \
  --transport kind-load \
  --namespace "$namespace" \
  --output "$kind_load_manifest" >/dev/null
while IFS= read -r service; do
  repository="$(simplematch_local_image_inventory_repository "$service")"
  grep -Fq "image: ${repository}:local" "$kind_load_manifest" || {
    printf 'kind-load render changed local image for %s\n' "$service" >&2
    exit 1
  }
done < <(simplematch_local_image_inventory_local_overlay_services)

# A renderer failure must not destroy a previously valid output file. Inject a
# failing kubectl executable after argument validation and verify atomic replace.
printf '%s\n' 'previous-valid-manifest' >"$atomic_target"
mkdir -p "$fake_bin"
cat >"$fake_bin/kubectl" <<'EOF_KUBECTL'
#!/usr/bin/env bash
printf '%s\n' 'partial-invalid-output'
exit 17
EOF_KUBECTL
chmod +x "$fake_bin/kubectl"
if PATH="$fake_bin:$PATH" bash "$renderer" \
    --transport kind-load \
    --namespace "$namespace" \
    --output "$atomic_target" >/dev/null 2>&1; then
  printf '%s\n' 'renderer unexpectedly succeeded with failing kubectl' >&2
  exit 1
fi
[[ "$(cat "$atomic_target")" == 'previous-valid-manifest' ]] || {
  printf '%s\n' 'failed render replaced the previous valid manifest' >&2
  exit 1
}

printf '%s\n' 'Local digest-based manifest rendering contract passed.'
