#!/usr/bin/env bash
set -Eeuo pipefail
IFS=$'\n\t'

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd -- "$script_dir/.." && pwd)"
# shellcheck source=scripts/lib/local-common.sh
source "$script_dir/lib/local-common.sh"
# shellcheck source=scripts/lib/local-kind.sh
source "$script_dir/lib/local-kind.sh"
# shellcheck source=scripts/lib/local-image-transport.sh
source "$script_dir/lib/local-image-transport.sh"
manager="$script_dir/manage-simplematch-live.sh"
registry_manager="$script_dir/manage-local-registry.sh"
report="$script_dir/local-resource-report.sh"
publisher="$script_dir/publish-local-images.sh"
renderer="$script_dir/render-local-kubernetes-manifest.sh"

baseline_file="$(mktemp "${TMPDIR:-/tmp}/simplematch-kind-baseline.XXXXXX.json")"
current_file="$(mktemp "${TMPDIR:-/tmp}/simplematch-kind-current.XXXXXX.json")"
report_file="$(mktemp "${TMPDIR:-/tmp}/simplematch-local-resource-report.XXXXXX.txt")"
render_lock="$(mktemp "${TMPDIR:-/tmp}/simplematch-registry-render-lock.XXXXXX")"
rendered_manifest="$(mktemp "${TMPDIR:-/tmp}/simplematch-registry-rendered.XXXXXX.yaml")"
rm -f "$baseline_file" "$current_file" "$render_lock"

export SIMPLEMATCH_LOCAL_RESOURCE_BASELINE_FILE="$baseline_file"
export SIMPLEMATCH_LOCAL_RESOURCE_BASELINE_TIMEOUT_SECONDS="${SIMPLEMATCH_LOCAL_RESOURCE_BASELINE_TIMEOUT_SECONDS:-180}"

smoke_namespace="simplematch-registry-pull-smoke"
smoke_image_tag="registry-smoke-${GITHUB_RUN_ID:-local}-${GITHUB_RUN_ATTEMPT:-0}-$$"
smoke_source_image="simplematch-matching:${smoke_image_tag}"
smoke_registry_endpoint="$(simplematch_registry_endpoint)"
smoke_repository="${smoke_registry_endpoint}/simplematch-matching"
smoke_registry_tag="${smoke_repository}:${smoke_image_tag}"

cleanup() {
  set +e
  if kind get clusters 2>/dev/null | grep -Fxq simplematch-live; then
    simplematch_kind_delete_disposable_namespace kind-simplematch-live "$smoke_namespace" 120 >/dev/null 2>&1 || true
  fi
  bash "$manager" delete >/dev/null 2>&1 || true
  bash "$registry_manager" delete --purge-data >/dev/null 2>&1 || true
  docker image rm "$smoke_registry_tag" >/dev/null 2>&1 || true
  docker image rm "$smoke_source_image" >/dev/null 2>&1 || true
  rm -f "$baseline_file" "$current_file" "$report_file" "$render_lock" "$rendered_manifest"
}
trap cleanup EXIT

for tool in docker kind kubectl jq; do
  command -v "$tool" >/dev/null 2>&1 || {
    printf '%s is required for live kind resource smoke test\n' "$tool" >&2
    exit 1
  }
done

docker info >/dev/null 2>&1 || {
  printf '%s\n' 'Docker daemon is not reachable' >&2
  exit 1
}

if kind get clusters 2>/dev/null | grep -Fxq simplematch-live; then
  printf '%s\n' 'refusing to run integration smoke with pre-existing simplematch-live cluster' >&2
  exit 1
fi

bash "$manager" create
[[ -s "$baseline_file" ]] || {
  printf '%s\n' 'manager create did not establish resource baseline' >&2
  exit 1
}

bash "$manager" verify
bash "$report" --baseline "$baseline_file" --output "$current_file" >"$report_file"

jq -e '
  .schema_version == 1
  and .kind.present == true
  and (.kind.nodes | length) == 4
  and (.kubernetes.disposable_namespaces | length) == 0
  and (.kubernetes.non_baseline_pods | length) == 0
  and .kubernetes.pv_count == 0
' "$baseline_file" >/dev/null

jq -e '
  .schema_version == 1
  and .kind.present == true
  and (.kind.nodes | length) == 4
' "$current_file" >/dev/null

grep -Fq 'Growth relative to baseline:' "$report_file"
grep -Eq 'assessment=(NO_CONTAINERD_GROWTH|IDLE_RESIDUAL_GROWTH)' "$report_file"
grep -Fq 'cluster_idle=true' "$report_file"

# Exercise the production publication path without building the real Matching
# binary. Reuse registry:3 as harmless content but expose it under the canonical
# matching source repository with a unique tag. The publisher must derive that
# repository from the shared inventory, push it, emit the digest lockfile, and
# remove its transient local-registry host tag.
docker tag registry:3 "$smoke_source_image"
bash "$publisher" \
  --tag "$smoke_image_tag" \
  --service matching \
  --output "$render_lock" >/dev/null
simplematch_local_image_lock_validate_file "$render_lock"
smoke_image="$(simplematch_local_image_lock_digest_reference "$render_lock" matching)"
[[ "$smoke_image" == "${smoke_repository}@sha256:"* ]] || {
  printf 'publisher emitted unexpected Matching digest reference: %s\n' "$smoke_image" >&2
  exit 1
}
if docker image inspect "$smoke_registry_tag" >/dev/null 2>&1; then
  printf 'publisher left transient host registry tag behind: %s\n' "$smoke_registry_tag" >&2
  exit 1
fi

# Exercise the same lockfile -> transient Kustomize -> digest-pinned manifest
# path used by certification. The smoke payload is never applied as Matching;
# it only verifies the configuration transformation with a real published digest.
bash "$renderer" \
  --image-lock "$render_lock" \
  --namespace "$smoke_namespace" \
  --output "$rendered_manifest" >/dev/null
grep -Fq "image: ${smoke_image}" "$rendered_manifest" || {
  printf '%s\n' 'registry renderer did not digest-pin the Matching image' >&2
  exit 1
}
grep -Fq "namespace: ${smoke_namespace}" "$rendered_manifest" || {
  printf '%s\n' 'registry renderer did not apply the requested namespace' >&2
  exit 1
}

node_has_smoke_repository() {
  local node="$1"

  docker exec "$node" crictl images --output=json |
    jq -e --arg repository "$smoke_repository" '
      [.images[]? | ((.repoTags // []) + (.repoDigests // []))[]?]
      | any(startswith($repository + ":") or startswith($repository + "@"))
    ' >/dev/null
}

# Prove registry transport remains demand-driven: schedule exactly one Pod on
# worker slot 0 and inspect CRI image metadata rather than parsing crictl's
# human-readable table. Only the scheduled node may acquire this repository.
simplematch_kind_create_disposable_namespace \
  kind-simplematch-live "$smoke_namespace" local-registry-pull-smoke registry-pull-smoke
kubectl --context kind-simplematch-live -n "$smoke_namespace" run registry-pull-smoke \
  --image="$smoke_image" \
  --image-pull-policy=IfNotPresent \
  --restart=Never \
  --overrides='{"spec":{"nodeSelector":{"simplematch.io/worker-slot":"0"}}}' >/dev/null
kubectl --context kind-simplematch-live -n "$smoke_namespace" wait \
  --for=condition=Ready pod/registry-pull-smoke --timeout=120s >/dev/null

scheduled_node="$(kubectl --context kind-simplematch-live -n "$smoke_namespace" get pod registry-pull-smoke -o jsonpath='{.spec.nodeName}')"
[[ "$scheduled_node" == simplematch-live-worker ]] || {
  printf 'registry pull smoke scheduled on unexpected node: %s\n' "$scheduled_node" >&2
  exit 1
}

node_has_smoke_repository simplematch-live-worker || {
  printf '%s\n' 'scheduled worker CRI metadata does not contain the local-registry smoke repository' >&2
  exit 1
}
for node in simplematch-live-control-plane simplematch-live-worker2 simplematch-live-worker3; do
  if node_has_smoke_repository "$node"; then
    printf 'local-registry smoke repository was unexpectedly present on %s\n' "$node" >&2
    exit 1
  fi
done

simplematch_kind_delete_disposable_namespace kind-simplematch-live "$smoke_namespace" 120

bash "$manager" delete
if kind get clusters 2>/dev/null | grep -Fxq simplematch-live; then
  printf '%s\n' 'canonical kind cluster still exists after delete' >&2
  exit 1
fi

bash "$registry_manager" delete --purge-data
docker image rm "$smoke_source_image" >/dev/null 2>&1 || true
trap - EXIT
rm -f "$baseline_file" "$current_file" "$report_file" "$render_lock" "$rendered_manifest"

printf '%s\n' 'Live kind canonical publication, digest rendering, and on-demand registry transport smoke test passed.'