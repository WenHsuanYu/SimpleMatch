#!/usr/bin/env bash
set -Eeuo pipefail
IFS=$'\n\t'
trap 'printf "Local registry/resource lifecycle contract failed at line %s\n" "$LINENO" >&2' ERR

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
  "$script_dir/normalize-local-images-for-kind.sh"
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
normalizer="$script_dir/normalize-local-images-for-kind.sh"
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
normalizer_dockerfile="$repo_root/deploy/docker/Dockerfile.kind-normalized"

# Step 3: registry and kind integration remain one explicit local infrastructure
# boundary, but the endpoint is configurable within the local-lab interface.
grep -Fq 'registry:3' "$registry_lib"
grep -Fq '/etc/containerd/certs.d/' "$registry_lib"
grep -Fq 'local-registry-hosting' "$registry_lib"
grep -Fq 'docker network connect' "$registry_lib"
grep -Fq 'simplematch-local-registry-data' "$registry_lib"
grep -Fq 'simplematch_registry_verify_container_identity' "$registry_lib"
grep -Fq '.Config.Image' "$registry_lib"
grep -Fq '.HostConfig.RestartPolicy.Name' "$registry_lib"
grep -Fq '/var/lib/registry' "$registry_lib"
grep -Fq 'docker port "$SIMPLEMATCH_LOCAL_REGISTRY_NAME" 5000/tcp' "$registry_lib"
grep -Fq 'simplematch_registry_connect_kind_cluster' "$manager"
grep -Fq 'simplematch_registry_verify' "$manager"
grep -Fq 'containerdConfigPatches:' "$kind_config"
grep -Fq 'config_path = "/etc/containerd/certs.d"' "$kind_config"

# Step 1: lifecycle label is the only automatic namespace deletion authority,
# and namespace/PV cleanup must finish before node image pruning.
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
if grep -Fq -- '--pack-caches' "$cleaner" || grep -Fq 'pack-cache-' "$cleaner"; then
  printf '%s\n' 'routine cleanup still guesses Pack-cache ownership by a global name pattern' >&2
  exit 1
fi
cleaner_aggressive_line="$(grep -n 'if \[\[ "$aggressive" == true \]\]' "$cleaner" | cut -d: -f1)"
cleaner_builder_line="$(grep -n 'docker builder prune --all --force' "$cleaner" | cut -d: -f1)"
[[ -n "$cleaner_aggressive_line" && -n "$cleaner_builder_line" && "$cleaner_aggressive_line" -lt "$cleaner_builder_line" ]] || {
  printf '%s\n' 'daemon-wide builder cleanup must remain behind routine-cleanup aggressive opt-in' >&2
  exit 1
}

# Step 2: resource reporting is read-only, baseline-aware, internally validated,
# and bound to one exact kind generation. Manager baseline creation reuses the
# bounded whole-snapshot collector rather than bypassing it.
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
grep -Fq 'local-resource-report.sh' "$manager"
grep -Fq -- '--no-baseline --json' "$manager"
if grep -Fq 'simplematch_local_resource_snapshot "$cluster_name"' "$manager"; then
  printf '%s\n' 'kind manager bypasses bounded whole-snapshot collection when establishing its baseline' >&2
  exit 1
fi

# Steps 3-5: registry is the default transport, kind-load is an explicit fallback,
# the fallback retains its normalizer, and registry publication/rendering owns the
# digest-lock path.
grep -Fq 'SIMPLEMATCH_LOCAL_IMAGE_TRANSPORT_DEFAULT="registry"' "$transport_lib"
grep -Fq 'registry|kind-load' "$transport_lib"
grep -Fq 'simplematch_local_image_transport_matching_reference' "$transport_lib"
grep -Fq 'simplematch_local_image_transport_matching_digest' "$transport_lib"
grep -Fq 'simplematch_local_image_lock_digest_reference' "$transport_lib"
grep -Fq 'simplematch_registry_endpoint' "$transport_lib"
grep -Fq 'expected_registry_repository' "$transport_lib"
grep -Fq 'simplematch_registry_verify "$cluster_name"' "$transport_prepare"
grep -Fq 'publish-local-images.sh' "$transport_prepare"
grep -Fq 'normalize-local-images-for-kind.sh' "$transport_prepare"
grep -Fq 'kind load docker-image' "$transport_prepare"
grep -Fq 'Dockerfile.kind-normalized' "$normalizer"
[[ -f "$normalizer_dockerfile" ]] || {
  printf '%s\n' 'legacy kind-load transfer Dockerfile is missing' >&2
  exit 1
}

# Certification may be modular internally, but it must preserve the staged
# transport contract at its public orchestration boundary.
grep -Fq 'local-certification-framework.sh' "$certification"
grep -Fq 'local-certification-kafka.sh' "$certification"
grep -Fq 'local-certification-kubernetes.sh' "$certification"
grep -Fq 'local-certification-connect.sh' "$certification"
grep -Fq 'local-certification-workloads.sh' "$certification"
grep -Fq 'local-certification-bootstrap.sh' "$certification"
grep -Fq 'local-certification-run.sh' "$certification"
grep -Fq 'run_logged()' "$framework_lib"
grep -Fq -- '--image-transport' "$framework_lib" "$bootstrap_lib"
grep -Fq 'simplematch_local_image_transport_validate "$image_transport"' "$bootstrap_lib"
grep -Fq 'image_transport=%s' "$bootstrap_lib"
grep -Fq -- '--transport "$image_transport"' "$run_lib"
grep -Fq 'simplematch_local_image_transport_matching_digest' "$run_lib"
grep -Fq 'simplematch_local_image_transport_matching_reference' "$run_lib"
grep -Fq 'prepare-local-kubernetes-images.sh' "$run_lib"
grep -Fq 'render-local-kubernetes-manifest.sh' "$kubernetes_lib"
grep -Fq -- '--image="$matching_runtime_image"' "$kubernetes_lib"
grep -Fq -- '--allow-shared-node' "$workloads_lib"
grep -Fq -- '--allow-local-image' "$workloads_lib"
grep -Fq -- '--connect-timeout 5 --max-time 15' "$connect_lib"

# Renderer is transport-aware. Registry substitutions are digest-based while
# kind-load is allowed to preserve tracked :local references.
grep -Fq -- '--transport MODE' "$renderer"
grep -Fq 'simplematch_local_image_transport_validate "$transport"' "$renderer"
grep -Fq 'digest: %s' "$renderer"
grep -Fq 'newName: %s' "$renderer"
grep -Fq -- '--allow-local-image' "$fleet_verifier"
grep -Fq '@sha256:' "$fleet_verifier"
grep -Fq 'LOCAL_IMAGE_REFERENCE' "$fleet_verifier"
grep -Fq 'digest_reference=' "$publisher"
grep -Fq 'docker push' "$publisher"

# Step 6: kubelet image GC is a local-cluster policy applied only after registry
# and digest rendering are available.
grep -Fq 'imageMaximumGCAge:' "$kind_config"
grep -Fq 'imageMinimumGCAge:' "$kind_config"
grep -Fq 'imageGCHighThresholdPercent:' "$kind_config"
grep -Fq 'imageGCLowThresholdPercent:' "$kind_config"
grep -Fq 'verify_kubelet_image_gc_policy' "$manager"

# Live smoke proves the default registry path is demand-driven rather than node
# preloading. The fallback is covered deterministically, not by changing this smoke.
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

# Step 7: hard reset delegates canonical owners and keeps daemon-global cleanup
# behind an explicit aggressive mode. Default deletion remains scoped to selected
# SimpleMatch runtimes.
grep -Fq 'manage-simplematch-live.sh' "$hard_reset"
grep -Fq 'simplematch_registry_delete' "$hard_reset"
grep -Fq 'unselected SimpleMatch kind cluster' "$hard_reset"
grep -Fq 'unselected SimpleMatch Compose project' "$hard_reset"
grep -Fq 'hard reset scope is ambiguous' "$hard_reset"
grep -Fq 'simplematch_contains "$cluster" "${kind_clusters[@]}"' "$hard_reset"
grep -Fq 'simplematch_contains "$project" "${compose_projects[@]}"' "$hard_reset"
grep -Fq 'simplematch_run bash "$script_dir/manage-simplematch-live.sh"' "$hard_reset"
if grep -Fq 'simplematch_run_best_effort bash "$script_dir/manage-simplematch-live.sh"' "$hard_reset"; then
  printf '%s\n' 'canonical cluster deletion can still bypass manager identity failure' >&2
  exit 1
fi
if grep -Fq 'pack-cache-' "$hard_reset"; then
  printf '%s\n' 'hard reset still guesses Pack-cache ownership by a global name pattern' >&2
  exit 1
fi
! grep -Fq -- '--rmi all' "$hard_reset"
hard_reset_aggressive_line="$(grep -n 'if \[\[ "$aggressive_unused_docker" == true \]\]' "$hard_reset" | cut -d: -f1)"
hard_reset_builder_line="$(grep -n 'docker builder prune --all --force' "$hard_reset" | cut -d: -f1)"
[[ -n "$hard_reset_aggressive_line" && -n "$hard_reset_builder_line" && "$hard_reset_aggressive_line" -lt "$hard_reset_builder_line" ]] || {
  printf '%s\n' 'daemon-wide builder cleanup must remain behind hard-reset aggressive opt-in' >&2
  exit 1
}

printf '%s\n' 'Seven-step local registry/resource lifecycle contract passed.'
