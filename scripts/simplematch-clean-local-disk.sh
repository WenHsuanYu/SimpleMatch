#!/usr/bin/env bash
set -Eeuo pipefail
IFS=$'\n\t'

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd -- "$script_dir/.." && pwd)"
# shellcheck source=scripts/lib/local-common.sh
source "$script_dir/lib/local-common.sh"
# shellcheck source=scripts/lib/local-kind.sh
source "$script_dir/lib/local-kind.sh"
# shellcheck source=scripts/lib/local-registry.sh
source "$script_dir/lib/local-registry.sh"

compose_project="${SIMPLEMATCH_CERTIFICATION_COMPOSE_PROJECT:-simplematch-local-production-like}"
compose_file="$repo_root/deploy/compose/kafka-connect.production-like.yml"
cluster_name="${SIMPLEMATCH_KIND_CLUSTER_NAME:-simplematch-live}"
namespace_timeout="${SIMPLEMATCH_NAMESPACE_CLEANUP_TIMEOUT_SECONDS:-180}"

delete_cluster=false
aggressive=false
purge_registry=false
report_details=false
SIMPLEMATCH_DRY_RUN=false

usage() {
  cat <<'EOF_USAGE'
Usage:
  scripts/simplematch-clean-local-disk.sh [options]

Default routine cleanup:
  - stop/remove the selected SimpleMatch production-like Compose project and its volumes
  - synchronously delete namespaces labeled simplematch.io/lifecycle=disposable
  - wait for namespace deletion and run-owned PV references to disappear
  - only then prune unused CRI images from each kind node

Options:
  --delete-cluster       Delete simplematch-live instead of pruning its node caches.
  --purge-registry       Remove local registry container AND registry data volume.
  --namespace-timeout S  Wait at most S seconds per namespace/PV cleanup.
  --report-details       Show baseline-aware resource snapshots before/after cleanup.
  --aggressive           Also prune globally unused Docker images, volumes, and builder cache.
                         This can affect other projects.
  --dry-run              Print destructive commands without executing them.
  -h, --help             Show this help.

The lifecycle label is the only automatic namespace-deletion authority. Historical
unlabeled namespaces are intentionally not inferred from their names; inspect and
label them explicitly before routine cleanup, or use an explicit cluster rebuild.

The default path preserves the reusable kind cluster, registry cache, daemon-wide
Pack/BuildKit caches, and unrelated Docker resources. It does not remove containerd
snapshots directly; kubelet/containerd remain responsible for runtime object
lifecycle. When a resource baseline exists, --report-details shows growth relative
to that exact kind cluster generation instead of applying a fixed disk-size threshold.
EOF_USAGE
}

remove_compose_project() {
  if ! docker compose version >/dev/null 2>&1; then
    simplematch_warn 'docker compose plugin is unavailable; skipping Compose cleanup'
    return 0
  fi
  [[ -f "$compose_file" ]] || simplematch_die "Compose file does not exist: $compose_file"
  simplematch_log "Remove Compose project $compose_project"
  simplematch_run docker compose \
    --project-name "$compose_project" \
    --file "$compose_file" \
    down --volumes --remove-orphans --timeout 30
}

report_resources() {
  [[ "$report_details" == true ]] || return 0
  [[ "$SIMPLEMATCH_DRY_RUN" != true ]] || return 0
  command -v kind >/dev/null 2>&1 || return 0
  bash "$script_dir/local-resource-report.sh" --cluster "$cluster_name" ||
    simplematch_warn 'baseline-aware local resource report failed'
}

while (($# > 0)); do
  case "$1" in
    --delete-cluster)
      delete_cluster=true
      shift
      ;;
    --purge-registry)
      purge_registry=true
      shift
      ;;
    --namespace-timeout)
      namespace_timeout="${2:?--namespace-timeout requires seconds}"
      shift 2
      ;;
    --report-details)
      report_details=true
      shift
      ;;
    --aggressive)
      aggressive=true
      shift
      ;;
    --dry-run)
      SIMPLEMATCH_DRY_RUN=true
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      usage >&2
      simplematch_die "unknown option: $1"
      ;;
  esac
done

[[ "$namespace_timeout" =~ ^[1-9][0-9]*$ ]] ||
  simplematch_die '--namespace-timeout must be a positive integer'

simplematch_require_command docker
docker info >/dev/null 2>&1 || simplematch_die 'Docker daemon is not reachable'

simplematch_log 'Docker disk usage before cleanup'
docker system df || true
report_resources

remove_compose_project

if [[ "$delete_cluster" == true ]]; then
  simplematch_require_command kind
  if simplematch_kind_exists "$cluster_name"; then
    manager_args=(delete)
    [[ "$SIMPLEMATCH_DRY_RUN" == true ]] && manager_args+=(--dry-run)
    simplematch_run bash "$script_dir/manage-simplematch-live.sh" "${manager_args[@]}"
  else
    simplematch_info "kind cluster already absent: $cluster_name"
  fi
else
  simplematch_require_command kind
  simplematch_kind_delete_disposable_namespaces \
    "$cluster_name" "$namespace_timeout"
  simplematch_kind_prune_unused_images "$cluster_name"
fi

if [[ "$purge_registry" == true ]]; then
  simplematch_registry_delete true
fi

if [[ "$aggressive" == true ]]; then
  simplematch_warn 'Aggressive mode affects unused Docker resources and builder caches from other projects.'
  simplematch_run docker image prune --all --force
  simplematch_run docker volume prune --force
  simplematch_run docker builder prune --all --force
  if docker buildx version >/dev/null 2>&1; then
    simplematch_run docker buildx prune --all --force
  fi
fi

simplematch_log 'Docker disk usage after cleanup'
docker system df || true
if [[ "$delete_cluster" == false ]]; then
  report_resources
fi

simplematch_log 'SimpleMatch routine disk cleanup completed'
