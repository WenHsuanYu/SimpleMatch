#!/usr/bin/env bash
set -Eeuo pipefail
IFS=$'\n\t'

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd -- "$script_dir/.." && pwd)"

files=(
  "$script_dir/lib/local-common.sh"
  "$script_dir/lib/local-kind.sh"
  "$script_dir/lib/local-registry.sh"
  "$script_dir/lib/local-resource.sh"
  "$script_dir/lib/local-resilience.sh"
  "$script_dir/lib/local-image-transport.sh"
  "$script_dir/manage-local-registry.sh"
  "$script_dir/prepare-local-kubernetes-images.sh"
  "$script_dir/publish-local-images.sh"
  "$script_dir/render-local-kubernetes-manifest.sh"
  "$script_dir/local-resource-report.sh"
  "$script_dir/simplematch-clean-local-disk.sh"
  "$script_dir/hard-reset-local.sh"
  "$script_dir/manage-simplematch-live.sh"
  "$script_dir/run-local-production-like-certification.sh"
  "$script_dir/run-local-resilience.sh"
  "$script_dir/verify-matching-fleet-live.sh"
  "$script_dir/test-local-image-transport.sh"
  "$script_dir/test-local-resource-report.sh"
  "$script_dir/test-local-resource-kind-integration.sh"
)

for file in "${files[@]}"; do
  bash -n "$file"
done

registry_lib="$script_dir/lib/local-registry.sh"
kind_lib="$script_dir/lib/local-kind.sh"
resource_lib="$script_dir/lib/local-resource.sh"
resilience_lib="$script_dir/lib/local-resilience.sh"
transport_lib="$script_dir/lib/local-image-transport.sh"
transport_prepare="$script_dir/prepare-local-kubernetes-images.sh"
cleaner="$script_dir/simplematch-clean-local-disk.sh"
hard_reset="$script_dir/hard-reset-local.sh"
publisher="$script_dir/publish-local-images.sh"
renderer="$script_dir/render-local-kubernetes-manifest.sh"
resource_report="$script_dir/local-resource-report.sh"
resource_integration="$script_dir/test-local-resource-kind-integration.sh"
manager="$script_dir/manage-simplematch-live.sh"
certification="$script_dir/run-local-production-like-certification.sh"
resilience="$script_dir/run-local-resilience.sh"
fleet_verifier="$script_dir/verify-matching-fleet-live.sh"
kind_config="$repo_root/deploy/kind/simplematch-live.yaml"

grep -Fq 'registry:3' "$registry_lib"
grep -Fq '/etc/containerd/certs.d/' "$registry_lib"
grep -Fq 'local-registry-hosting' "$registry_lib"
grep -Fq 'docker network connect' "$registry_lib"
grep -Fq 'simplematch-local-registry-data' "$registry_lib"

# The lifecycle label is the only automatic namespace deletion authority.
grep -Fq 'simplematch.io/lifecycle=disposable' "$kind_lib"
grep -Fq -- '-l simplematch.io/lifecycle=disposable' "$kind_lib"
grep -Fq 'simplematch_kind_create_disposable_namespace' "$kind_lib"
grep -Fq 'simplematch_kind_delete_disposable_namespace' "$kind_lib"
grep -Fq -- '--wait=true' "$kind_lib"
grep -Fq 'simplematch_kind_wait_claim_pvs_gone' "$kind_lib"
for legacy_prefix in 'simplematch-local-cert-*' 'simplematch-resilience-*' 'simplematch-rm1-*'; do
  if grep -Fq "$legacy_prefix" "$kind_lib"; then
    printf 'prefix-based namespace ownership remains in local-kind.sh: %s\n' "$legacy_prefix" >&2
    exit 1
  fi
done
if grep -Fq -- '--no-legacy-namespaces' "$cleaner"; then
  printf '%s\n' 'routine cleanup still exposes legacy prefix deletion' >&2
  exit 1
fi

grep -Fq 'simplematch_kind_delete_disposable_namespaces' "$cleaner"
grep -Fq 'simplematch_kind_prune_unused_images' "$cleaner"
grep -Fq 'local-resource-report.sh' "$cleaner"
delete_line="$(grep -n 'simplematch_kind_delete_disposable_namespaces' "$cleaner" | tail -1 | cut -d: -f1)"
prune_line="$(grep -n 'simplematch_kind_prune_unused_images' "$cleaner" | tail -1 | cut -d: -f1)"
[[ "$delete_line" -lt "$prune_line" ]] || {
  printf '%s\n' 'namespace cleanup must precede CRI image prune' >&2
  exit 1
}

# Resource reporting is baseline-aware, internally consistent, and tied to the exact kind generation.
grep -Fq 'simplematch_local_resource_snapshot' "$resource_lib"
grep -Fq 'simplematch_local_resource_cluster_fingerprint' "$resource_lib"
grep -Fq 'simplematch_local_resource_compare_files' "$resource_lib"
grep -Fq 'simplematch_local_resource_render_snapshot_file' "$resource_lib"
grep -Fq 'simplematch_local_resource_render_comparison_file' "$resource_lib"
grep -Fq 'map(.name) | unique' "$resource_lib"
grep -Fq 'map(.container_id) | unique' "$resource_lib"
grep -Fq '.kind.totals.containerd_bytes == ([.kind.nodes[].containerd_bytes] | add // 0)' "$resource_lib"
grep -Fq 'IDLE_RESIDUAL_GROWTH' "$resource_lib"
grep -Fq 'ACTIVE_WORKLOAD_GROWTH' "$resource_lib"
grep -Fq 'NO_CONTAINERD_GROWTH' "$resource_lib"
grep -Fq 'recycle_candidate' "$resource_lib"
grep -Fq -- '--write-baseline' "$resource_report"
grep -Fq -- '--baseline' "$resource_report"
grep -Fq 'simplematch_local_resource_assert_clean_baseline_json' "$resource_report"
grep -Fq 'simplematch_local_resource_render_snapshot_file' "$resource_report"
grep -Fq 'simplematch_local_resource_render_comparison_file' "$resource_report"
grep -Fq 'establish_resource_baseline' "$manager"
grep -Fq 'SIMPLEMATCH_LOCAL_RESOURCE_BASELINE_FILE' "$manager"

# The image transport policy is registry-only. Preparation owns publication side effects;
# direct image normalization and kind-node imports must not be executable dependencies.
grep -Fq 'SIMPLEMATCH_LOCAL_IMAGE_TRANSPORT_DEFAULT="registry"' "$transport_lib"
grep -Fq 'simplematch_local_image_transport_validate' "$transport_lib"
grep -Fq 'simplematch_local_image_lock_digest_reference' "$transport_lib"
grep -Fq 'simplematch_local_image_transport_matching_reference' "$transport_lib"
if grep -Fq 'kind-load' "$transport_lib"; then
  printf '%s\n' 'transport policy still contains the removed kind-load mode' >&2
  exit 1
fi
grep -Fq 'simplematch_registry_verify "$cluster_name"' "$transport_prepare"
grep -Fq 'publish-local-images.sh' "$transport_prepare"
if grep -Fq 'normalize-local-images-for-kind.sh' "$transport_prepare"; then
  printf '%s\n' 'image preparation still depends on the removed kind normalizer' >&2
  exit 1
fi
if grep -Fq 'kind load docker-image' "$transport_prepare"; then
  printf '%s\n' 'image preparation still imports images directly into kind nodes' >&2
  exit 1
fi

# Certification consumes the transport abstraction during the staged runner cutover. It must not
# own direct-import implementation details; the next cutover slice removes the public switch itself.
grep -Fq 'source "$script_dir/lib/local-image-transport.sh"' "$certification"
grep -Fq 'SIMPLEMATCH_LOCAL_IMAGE_TRANSPORT_DEFAULT' "$certification"
grep -Fq -- '--image-transport' "$certification"
grep -Fq 'prepare-local-kubernetes-images.sh' "$certification"
grep -Fq 'render-local-kubernetes-manifest.sh' "$certification"
grep -Fq 'simplematch_local_image_transport_matching_digest' "$certification"
grep -Fq 'simplematch_local_image_transport_matching_reference' "$certification"
grep -Fq 'run_refreshable_logged kubernetes-image-transport' "$certification"
grep -Fq -- '--image="$matching_runtime_image"' "$certification"
if grep -Fq 'normalize-local-images-for-kind.sh' "$certification"; then
  printf '%s\n' 'certification runner still owns kind image normalization' >&2
  exit 1
fi
if grep -Fq 'kind load docker-image' "$certification"; then
  printf '%s\n' 'certification runner still owns kind image loading' >&2
  exit 1
fi

# Rendering is registry-backed and digest pinned. The compatibility option remains only long enough
# to reject a removed transport explicitly during this staged cutover.
grep -Fq -- '--transport MODE' "$renderer"
grep -Fq 'simplematch_local_image_transport_validate "$transport"' "$renderer"
grep -Fq 'digest: %s' "$renderer"
grep -Fq 'newName: %s' "$renderer"
if grep -Fq 'kind-load preserves' "$renderer"; then
  printf '%s\n' 'renderer still documents mutable kind-load rendering' >&2
  exit 1
fi

grep -Fq 'digest_reference=' "$publisher"
grep -Fq 'docker push' "$publisher"

# The fleet verifier still owns its independent explicit-local-image verification mode until the
# certification runner no longer has any local-image call sites.
grep -Fq -- '--allow-local-image IMAGE' "$fleet_verifier"
grep -Fq 'LOCAL_IMAGE_REFERENCE' "$fleet_verifier"

# The live smoke exercises canonical lifecycle and proves demand-driven registry pulling.
grep -Fq 'bash "$manager" create' "$resource_integration"
grep -Fq 'bash "$manager" verify' "$resource_integration"
grep -Fq -- '--baseline "$baseline_file"' "$resource_integration"
grep -Fq 'registry-pull-smoke' "$resource_integration"
grep -Fq 'simplematch.io/worker-slot' "$resource_integration"
grep -Fq 'unexpectedly preloaded' "$resource_integration"
grep -Fq 'bash "$manager" delete' "$resource_integration"
grep -Fq 'trap cleanup EXIT' "$resource_integration"
grep -Fq 'delete --purge-data' "$resource_integration"

# Both run owners must establish lifecycle ownership and use the same teardown primitive.
grep -Fq 'simplematch_kind_create_disposable_namespace' "$certification"
grep -Fq 'local-production-like-certification' "$certification"
grep -Fq 'simplematch_kind_namespace_is_disposable' "$certification"
grep -Fq 'simplematch_kind_delete_disposable_namespace' "$certification"
grep -Fq 'market_reference.sha256' "$certification"
! grep -Fq 'market-reference.sha256' "$certification"
grep -Fq 'simplematch_kind_create_disposable_namespace' "$resilience"
grep -Fq 'local-resilience' "$resilience"
grep -Fq 'simplematch_kind_delete_disposable_namespace' "$resilience"
grep -Fq '.metadata.labels["simplematch.io/lifecycle"] == "disposable"' "$resilience_lib"
grep -Fq '.metadata.labels["simplematch.io/run-id"] == $run_id' "$resilience_lib"

grep -Fq 'manage-simplematch-live.sh' "$hard_reset"
grep -Fq 'simplematch_registry_delete' "$hard_reset"
! grep -Fq -- '--rmi all' "$hard_reset"

grep -Fq 'simplematch_registry_connect_kind_cluster' "$manager"
grep -Fq 'simplematch_registry_verify' "$manager"
grep -Fq 'containerdConfigPatches:' "$kind_config"
grep -Fq 'config_path = "/etc/containerd/certs.d"' "$kind_config"

printf '%s\n' 'Local registry, registry-only image transport, and resource lifecycle contract passed.'
