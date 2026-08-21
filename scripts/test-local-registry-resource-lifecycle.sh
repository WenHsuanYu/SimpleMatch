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
  "$script_dir/lib/local-certification-framework.sh"
  "$script_dir/lib/local-certification-kafka.sh"
  "$script_dir/lib/local-certification-kubernetes.sh"
  "$script_dir/lib/local-certification-connect.sh"
  "$script_dir/lib/local-certification-workloads.sh"
  "$script_dir/lib/local-certification-bootstrap.sh"
  "$script_dir/lib/local-certification-run.sh"
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
  "$script_dir/test-local-image-rendering.sh"
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
framework_lib="$script_dir/lib/local-certification-framework.sh"
bootstrap_lib="$script_dir/lib/local-certification-bootstrap.sh"
kubernetes_lib="$script_dir/lib/local-certification-kubernetes.sh"
connect_lib="$script_dir/lib/local-certification-connect.sh"
workloads_lib="$script_dir/lib/local-certification-workloads.sh"
run_lib="$script_dir/lib/local-certification-run.sh"
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

# Registry and kind integration remain one explicit local infrastructure boundary.
grep -Fq 'registry:3' "$registry_lib"
grep -Fq '/etc/containerd/certs.d/' "$registry_lib"
grep -Fq 'local-registry-hosting' "$registry_lib"
grep -Fq 'docker network connect' "$registry_lib"
grep -Fq 'simplematch-local-registry-data' "$registry_lib"
grep -Fq 'simplematch_registry_connect_kind_cluster' "$manager"
grep -Fq 'simplematch_registry_verify' "$manager"
grep -Fq 'containerdConfigPatches:' "$kind_config"
grep -Fq 'config_path = "/etc/containerd/certs.d"' "$kind_config"

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

# Resource reporting remains baseline-aware and bound to an exact kind generation.
grep -Fq 'simplematch_local_resource_snapshot' "$resource_lib"
grep -Fq 'simplematch_local_resource_cluster_fingerprint' "$resource_lib"
grep -Fq 'simplematch_local_resource_compare_files' "$resource_lib"
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
grep -Fq 'establish_resource_baseline' "$manager"
grep -Fq 'SIMPLEMATCH_LOCAL_RESOURCE_BASELINE_FILE' "$manager"

# Registry is the only Kubernetes application-image transport. The policy module
# owns lock semantics only; publication and rendering own side effects.
grep -Fq 'simplematch_local_image_transport_reject_legacy_override' "$transport_lib"
grep -Fq 'simplematch_local_image_lock_digest_reference' "$transport_lib"
grep -Fq 'simplematch_local_image_lock_digest' "$transport_lib"
if grep -Fq 'kind-load' "$transport_lib"; then
  printf '%s\n' 'transport policy still contains the removed kind-load mode' >&2
  exit 1
fi
grep -Fq 'simplematch_registry_verify "$cluster_name"' "$transport_prepare"
grep -Fq 'publish-local-images.sh' "$transport_prepare"
for removed_reference in 'normalize-local-images-for-kind.sh' 'kind load docker-image'; do
  if grep -Fq "$removed_reference" "$transport_prepare"; then
    printf 'image preparation still contains removed direct-import behavior: %s\n' "$removed_reference" >&2
    exit 1
  fi
done

# Certification is an orchestrator over cohesive domain modules, not a transport switch.
grep -Fq 'local-certification-framework.sh' "$certification"
grep -Fq 'local-certification-kafka.sh' "$certification"
grep -Fq 'local-certification-kubernetes.sh' "$certification"
grep -Fq 'local-certification-connect.sh' "$certification"
grep -Fq 'local-certification-workloads.sh' "$certification"
grep -Fq 'local-certification-bootstrap.sh' "$certification"
grep -Fq 'local-certification-run.sh' "$certification"
grep -Fq 'run_logged()' "$framework_lib"
grep -Fq 'simplematch_local_image_transport_reject_legacy_override' "$bootstrap_lib"
grep -Fq 'simplematch_local_image_lock_digest "$image_lock" matching' "$run_lib"
grep -Fq 'simplematch_local_image_lock_digest_reference "$image_lock" matching' "$run_lib"
grep -Fq 'prepare-local-kubernetes-images.sh' "$run_lib"
grep -Fq 'render-local-kubernetes-manifest.sh' "$kubernetes_lib"
grep -Fq -- '--image="$matching_runtime_image"' "$kubernetes_lib"
grep -Fq -- '--allow-shared-node' "$workloads_lib"
grep -Fq -- '--connect-timeout 5 --max-time 15' "$connect_lib"

for removed_reference in '--image-transport' 'SIMPLEMATCH_LOCAL_IMAGE_TRANSPORT_DEFAULT' 'normalize-local-images-for-kind.sh' 'kind load docker-image' '--allow-local-image'; do
  if grep -Fq -- "$removed_reference" "$certification" "$bootstrap_lib" "$run_lib" "$kubernetes_lib" "$workloads_lib"; then
    printf 'certification still exposes removed image-transport behavior: %s\n' "$removed_reference" >&2
    exit 1
  fi
done

# Renderer is digest-only and has no compatibility transport option.
grep -Fq 'simplematch_local_image_transport_reject_legacy_override' "$renderer"
grep -Fq 'digest: %s' "$renderer"
grep -Fq 'newName: %s' "$renderer"
if grep -Fq -- '--transport' "$renderer"; then
  printf '%s\n' 'renderer still exposes the removed transport selector' >&2
  exit 1
fi
grep -Fq 'digest_reference=' "$publisher"
grep -Fq 'docker push' "$publisher"

# Live smoke proves demand-driven registry pulling rather than node preloading.
grep -Fq 'bash "$manager" create' "$resource_integration"
grep -Fq 'bash "$manager" verify' "$resource_integration"
grep -Fq -- '--baseline "$baseline_file"' "$resource_integration"
grep -Fq 'registry-pull-smoke' "$resource_integration"
grep -Fq 'simplematch.io/worker-slot' "$resource_integration"
grep -Fq 'unexpectedly preloaded' "$resource_integration"
grep -Fq 'bash "$manager" delete' "$resource_integration"
grep -Fq 'trap cleanup EXIT' "$resource_integration"
grep -Fq 'delete --purge-data' "$resource_integration"

# Both run owners still share the same namespace lifecycle primitives.
grep -Fq 'simplematch_kind_create_disposable_namespace' "$kubernetes_lib"
grep -Fq 'local-production-like-certification' "$kubernetes_lib" "$bootstrap_lib"
grep -Fq 'simplematch_kind_namespace_is_disposable' "$bootstrap_lib"
grep -Fq 'simplematch_kind_delete_disposable_namespace' "$framework_lib"
grep -Fq 'market_reference.sha256' "$kubernetes_lib"
! grep -Fq 'market-reference.sha256' "$kubernetes_lib"
grep -Fq 'simplematch_kind_create_disposable_namespace' "$resilience"
grep -Fq 'local-resilience' "$resilience"
grep -Fq 'simplematch_kind_delete_disposable_namespace' "$resilience"
grep -Fq '.metadata.labels["simplematch.io/lifecycle"] == "disposable"' "$resilience_lib"
grep -Fq '.metadata.labels["simplematch.io/run-id"] == $run_id' "$resilience_lib"

grep -Fq 'manage-simplematch-live.sh' "$hard_reset"
grep -Fq 'simplematch_registry_delete' "$hard_reset"
! grep -Fq -- '--rmi all' "$hard_reset"

[[ ! -e "$script_dir/normalize-local-images-for-kind.sh" ]] || {
  printf '%s\n' 'removed kind normalizer script still exists' >&2
  exit 1
}
[[ ! -e "$repo_root/deploy/docker/Dockerfile.kind-normalized" ]] || {
  printf '%s\n' 'removed kind normalizer Dockerfile still exists' >&2
  exit 1
}

printf '%s\n' 'Local registry, certification modularity, and resource lifecycle contract passed.'
