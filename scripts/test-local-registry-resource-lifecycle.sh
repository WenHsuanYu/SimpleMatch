#!/usr/bin/env bash
set -Eeuo pipefail
IFS=$'\n\t'
trap 'printf "Local registry/resource lifecycle contract failed at line %s\n" "$LINENO" >&2' ERR

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd -- "$script_dir/.." && pwd)"

kind_lib="$script_dir/lib/local-kind.sh"
registry_lib="$script_dir/lib/local-registry.sh"
resource_lib="$script_dir/lib/local-resource.sh"
transport_lib="$script_dir/lib/local-image-transport.sh"
framework_lib="$script_dir/lib/local-certification-framework.sh"
bootstrap_lib="$script_dir/lib/local-certification-bootstrap.sh"
kubernetes_lib="$script_dir/lib/local-certification-kubernetes.sh"
workloads_lib="$script_dir/lib/local-certification-workloads.sh"
run_lib="$script_dir/lib/local-certification-run.sh"
cleaner="$script_dir/simplematch-clean-local-disk.sh"
hard_reset="$script_dir/hard-reset-local.sh"
manager="$script_dir/manage-simplematch-live.sh"
registry_manager="$script_dir/manage-local-registry.sh"
transport_prepare="$script_dir/prepare-local-kubernetes-images.sh"
normalizer="$script_dir/normalize-local-images-for-kind.sh"
publisher="$script_dir/publish-local-images.sh"
renderer="$script_dir/render-local-kubernetes-manifest.sh"
certification="$script_dir/run-local-production-like-certification.sh"
resilience="$script_dir/run-local-resilience.sh"
fleet_verifier="$script_dir/verify-matching-fleet-live.sh"
resource_report="$script_dir/local-resource-report.sh"
resource_integration="$script_dir/test-local-resource-kind-integration.sh"
kind_config="$repo_root/deploy/kind/simplematch-live.yaml"
normalizer_dockerfile="$repo_root/deploy/docker/Dockerfile.kind-normalized"

require_literal() {
  local literal="$1"
  shift
  grep -Fq -- "$literal" "$@" || {
    printf 'Required lifecycle contract is missing: %s\n' "$literal" >&2
    return 1
  }
}

reject_literal() {
  local literal="$1"
  shift
  if grep -Fq -- "$literal" "$@"; then
    printf 'Forbidden lifecycle contract remains: %s\n' "$literal" >&2
    return 1
  fi
}

for file in \
  "$kind_lib" "$registry_lib" "$resource_lib" "$transport_lib" \
  "$framework_lib" "$bootstrap_lib" "$kubernetes_lib" "$workloads_lib" "$run_lib" \
  "$cleaner" "$hard_reset" "$manager" "$registry_manager" "$transport_prepare" \
  "$normalizer" "$publisher" "$renderer" "$certification" "$resilience" "$fleet_verifier" \
  "$resource_report" "$resource_integration"; do
  bash -n "$file"
done

# Step 1 — explicit disposable ownership and fail-closed cleanup ordering.
require_literal 'simplematch.io/lifecycle=disposable' "$kind_lib"
require_literal '-l simplematch.io/lifecycle=disposable' "$kind_lib"
require_literal 'simplematch_kind_create_disposable_namespace' "$kubernetes_lib" "$resilience"
require_literal 'simplematch_kind_delete_disposable_namespace' "$framework_lib" "$resilience"
require_literal 'if ! namespaces="$(simplematch_kind_disposable_namespaces "$context")"; then' "$kind_lib"
require_literal 'if ! claim_namespaces="$(simplematch_kind_claim_namespaces "$context")"; then' "$kind_lib"
for legacy_prefix in 'simplematch-local-cert-*' 'simplematch-resilience-*' 'simplematch-rm1-*'; do
  reject_literal "$legacy_prefix" "$kind_lib"
done
require_literal 'simplematch_kind_delete_disposable_namespaces' "$cleaner"
require_literal 'simplematch_kind_prune_unused_images' "$cleaner"
delete_line="$(grep -n 'simplematch_kind_delete_disposable_namespaces' "$cleaner" | tail -1 | cut -d: -f1)"
prune_line="$(grep -n 'simplematch_kind_prune_unused_images' "$cleaner" | tail -1 | cut -d: -f1)"
[[ -n "$delete_line" && -n "$prune_line" && "$delete_line" -lt "$prune_line" ]] || {
  printf '%s\n' 'namespace/PV cleanup must complete before CRI image prune' >&2
  exit 1
}

# Step 2 — read-only snapshots, exact-generation baseline, and growth classes.
require_literal 'simplematch_local_resource_snapshot' "$resource_lib"
require_literal 'simplematch_local_resource_cluster_fingerprint' "$resource_lib"
require_literal 'io.containerd.content.v1.content' "$resource_lib"
require_literal 'io.containerd.snapshotter.v1.overlayfs' "$resource_lib"
require_literal 'exited_containers' "$resource_lib"
require_literal 'notready_sandboxes' "$resource_lib"
require_literal 'docker system df' "$resource_lib"
require_literal 'registry_bytes' "$resource_lib"
require_literal 'NO_CONTAINERD_GROWTH' "$resource_lib"
require_literal 'ACTIVE_WORKLOAD_GROWTH' "$resource_lib"
require_literal 'IDLE_RESIDUAL_GROWTH' "$resource_lib"
require_literal 'recycle_candidate' "$resource_lib"
require_literal '--write-baseline' "$resource_report"
require_literal '--baseline' "$resource_report"
require_literal 'establish_resource_baseline' "$manager"
reject_literal 'GB threshold' "$resource_lib"

# Steps 3–5 — registry default, explicit fallback, isolated normalization,
# atomic digest publication, and transport-aware rendering.
require_literal 'SIMPLEMATCH_LOCAL_IMAGE_TRANSPORT_DEFAULT="registry"' "$transport_lib"
require_literal 'registry|kind-load' "$transport_lib"
require_literal 'simplematch_registry_validate_configuration' "$registry_lib"
require_literal 'local registry host must remain localhost' "$registry_lib"
require_literal 'publish-local-images.sh' "$transport_prepare"
require_literal 'normalize-local-images-for-kind.sh' "$transport_prepare"
require_literal 'kind load docker-image' "$transport_prepare"
[[ -f "$normalizer_dockerfile" ]] || {
  printf '%s\n' 'legacy kind-load normalization Dockerfile is missing' >&2
  exit 1
}
require_literal 'docker push' "$publisher"
require_literal 'mv -f -- "$temp_lock" "$output_file"' "$publisher"
require_literal 'digest_reference=' "$publisher"
require_literal '--transport MODE' "$renderer"
require_literal 'digest: %s' "$renderer"
require_literal 'newName: %s' "$renderer"
require_literal 'full registry render still contains at least one mutable :local image' "$renderer"
require_literal '--image-transport' "$framework_lib" "$bootstrap_lib"
require_literal 'simplematch_local_image_transport_validate "$image_transport"' "$bootstrap_lib"
require_literal '--transport "$image_transport"' "$run_lib"
require_literal 'simplematch_local_image_transport_matching_digest' "$run_lib"
require_literal 'simplematch_local_image_transport_matching_reference' "$run_lib"
require_literal '--allow-local-image' "$workloads_lib" "$fleet_verifier"

# Step 6 — local-only kubelet image GC, verified from effective node config. The
# live smoke contract checks CRI metadata structurally: one positive scheduled
# node assertion and negative assertions for every other canonical node.
require_literal 'imageMinimumGCAge:' "$kind_config"
require_literal 'imageMaximumGCAge:' "$kind_config"
require_literal 'imageGCHighThresholdPercent:' "$kind_config"
require_literal 'imageGCLowThresholdPercent:' "$kind_config"
require_literal 'verify_kubelet_image_gc_policy' "$manager"
require_literal 'registry-pull-smoke' "$resource_integration"
require_literal 'node_has_smoke_repository()' "$resource_integration"
require_literal 'crictl images --output=json' "$resource_integration"
require_literal 'node_has_smoke_repository simplematch-live-worker || {' "$resource_integration"
require_literal 'if node_has_smoke_repository "$node"; then' "$resource_integration"

# Step 7 — destructive ownership delegated to managers; daemon-global cleanup is
# explicit aggressive-only behavior.
require_literal 'simplematch_run bash "$script_dir/manage-simplematch-live.sh"' "$hard_reset"
require_literal 'registry_manager="$script_dir/manage-local-registry.sh"' "$hard_reset"
require_literal 'simplematch_run bash "$registry_manager" "${args[@]}"' "$hard_reset"
reject_literal 'simplematch_registry_delete' "$hard_reset"
reject_literal '--rmi all' "$hard_reset"
reject_literal 'pack-cache-' "$hard_reset"
require_literal 'if [[ "$aggressive_unused_docker" == true ]]; then' "$hard_reset"
require_literal 'docker builder prune --all --force' "$hard_reset"
require_literal 'docker buildx prune --all --force' "$hard_reset"
aggressive_line="$(grep -n 'if \[\[ "$aggressive_unused_docker" == true \]\]' "$hard_reset" | cut -d: -f1)"
builder_line="$(grep -n 'docker builder prune --all --force' "$hard_reset" | cut -d: -f1)"
[[ -n "$aggressive_line" && -n "$builder_line" && "$aggressive_line" -lt "$builder_line" ]] || {
  printf '%s\n' 'daemon-wide builder cleanup must remain behind aggressive opt-in' >&2
  exit 1
}

printf '%s\n' 'Seven-step local registry/resource lifecycle contract passed.'