#!/usr/bin/env bash

set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd -- "$script_dir/.." && pwd)"
# shellcheck source=scripts/lib/local-common.sh
source "$script_dir/lib/local-common.sh"
# shellcheck source=scripts/lib/local-kind.sh
source "$script_dir/lib/local-kind.sh"
# shellcheck source=scripts/lib/local-image-transport.sh
source "$script_dir/lib/local-image-transport.sh"

compose_file="$repo_root/deploy/compose/kafka-connect.production-like.yml"
compose_project="${SIMPLEMATCH_CERTIFICATION_COMPOSE_PROJECT:-simplematch-local-production-like}"
image_tag="${SIMPLEMATCH_LOCAL_IMAGE_TAG:-local}"
image_transport="${SIMPLEMATCH_LOCAL_IMAGE_TRANSPORT:-$SIMPLEMATCH_LOCAL_IMAGE_TRANSPORT_DEFAULT}"
evidence_dir="${SIMPLEMATCH_CERTIFICATION_EVIDENCE_DIR:-$repo_root/out/certification/local-production-like}"
image_lock="${SIMPLEMATCH_LOCAL_IMAGE_LOCK:-$evidence_dir/local-images.lock}"
matching_producer_config_file="${SIMPLEMATCH_KAFKA_PRODUCER_CONFIG_FILE:-$evidence_dir/matching-producer.config.txt}"
matching_capacity_evidence_file="${SIMPLEMATCH_KAFKA_CAPACITY_EVIDENCE_FILE:-$evidence_dir/kafka-capacity.properties}"
matching_capacity_workload_file="${SIMPLEMATCH_KAFKA_CAPACITY_WORKLOAD_FILE:-$repo_root/scripts/testdata/matching-topic-profile/local/capacity.properties}"
certification_trading_day="${SIMPLEMATCH_CERTIFICATION_TRADING_DAY:-$(date -u +%F)}"
local_postgres_password="${SIMPLEMATCH_LOCAL_POSTGRES_PASSWORD:-simplematch}"
if [[ ! "$local_postgres_password" =~ ^[A-Za-z0-9._~-]+$ ]]; then
  printf '%s\n' 'SIMPLEMATCH_LOCAL_POSTGRES_PASSWORD may contain only URL-safe local-lab characters.' >&2
  exit 1
fi
local_postgres_dsn="postgresql://simplematch:${local_postgres_password}@postgres:5432/simplematch"
namespace=""
kind_cluster="${SIMPLEMATCH_KIND_CLUSTER_NAME:-simplematch-live}"
kind_context="kind-${kind_cluster}"
dry_run=false
skip_build=false
skip_compose=false
skip_kubernetes=false
matching_fleet_only=false
keep_resources=false
resume=false
compose_started=false
kubernetes_namespace_created=false
failure_reason=""
failed_phase=""
completion_status="RUNNING"
completed_phases=()
certification_timeout_seconds="${SIMPLEMATCH_CERTIFICATION_TIMEOUT_SECONDS:-7200}"
namespace_cleanup_timeout="${SIMPLEMATCH_NAMESPACE_CLEANUP_TIMEOUT_SECONDS:-180}"
certification_deadline_epoch=0
phase_marker_directory=""
run_context_file=""
source_signature=""
matching_image_reference=""
compose_prefix=()
compose_command=()

# Certification domain behavior is split by responsibility. The top-level script
# owns configuration, phase ordering, and lifecycle only.
for certification_lib in \
  local-certification-framework.sh \
  local-certification-kafka.sh \
  local-certification-kubernetes.sh \
  local-certification-connect.sh \
  local-certification-workloads.sh; do
  # shellcheck source=/dev/null
  source "$script_dir/lib/$certification_lib"
done
unset certification_lib

# Bootstrap validates configuration and static prerequisites; run owns phase ordering.
# shellcheck source=/dev/null
source "$script_dir/lib/local-certification-bootstrap.sh"
# shellcheck source=/dev/null
source "$script_dir/lib/local-certification-run.sh"
