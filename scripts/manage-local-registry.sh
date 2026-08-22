#!/usr/bin/env bash
set -Eeuo pipefail
IFS=$'\n\t'

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/lib/local-common.sh
source "$script_dir/lib/local-common.sh"
# shellcheck source=scripts/lib/local-registry.sh
source "$script_dir/lib/local-registry.sh"

cluster_name="${SIMPLEMATCH_KIND_CLUSTER_NAME:-simplematch-live}"
SIMPLEMATCH_DRY_RUN=false

usage() {
  cat <<'EOF_USAGE'
Usage:
  scripts/manage-local-registry.sh create [--dry-run]
  scripts/manage-local-registry.sh connect [--cluster NAME] [--dry-run]
  scripts/manage-local-registry.sh verify [--cluster NAME] [--registry-only]
  scripts/manage-local-registry.sh delete [--purge-data] [--dry-run]

Commands:
  create       Create or start the repository-owned local OCI registry.
  connect      Configure an existing kind cluster to pull localhost:5001 images
               from the registry container.
  verify       Verify canonical registry identity and, by default, kind integration.
  delete       Remove the registry container. Registry data is preserved unless
               --purge-data is supplied.

Canonical registry identity is fixed by repository policy:
  container    simplematch-local-registry
  image        registry:3
  endpoint     localhost:5001
  data volume  simplematch-local-registry-data
  network      kind

Only the target kind cluster is selectable. Registry identity overrides are
rejected rather than allowing setup or cleanup to target a different Docker object.

Environment:
  SIMPLEMATCH_KIND_CLUSTER_NAME           default: simplematch-live
EOF_USAGE
}

[[ $# -ge 1 ]] || { usage >&2; exit 2; }
command_name="$1"
shift
purge_data=false
registry_only=false

while (($# > 0)); do
  case "$1" in
    --cluster)
      [[ $# -ge 2 ]] || simplematch_die '--cluster requires a value'
      cluster_name="$2"
      shift 2
      ;;
    --purge-data)
      purge_data=true
      shift
      ;;
    --registry-only)
      registry_only=true
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

case "$command_name" in
  create)
    simplematch_registry_create
    ;;
  connect)
    simplematch_registry_connect_kind_cluster "$cluster_name"
    ;;
  verify)
    if [[ "$registry_only" == true ]]; then
      simplematch_registry_verify
    else
      simplematch_registry_verify "$cluster_name"
    fi
    ;;
  delete)
    simplematch_registry_delete "$purge_data"
    ;;
  help)
    usage
    ;;
  *)
    usage >&2
    exit 2
    ;;
esac
