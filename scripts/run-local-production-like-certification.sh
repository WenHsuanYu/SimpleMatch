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
# shellcheck source=scripts/lib/local-certification-provenance.sh
source "$script_dir/lib/local-certification-provenance.sh"

compose_file="$repo_root/deploy/compose/kafka-connect.production-like.yml"
compose_project="${SIMPLEMATCH_CERTIFICATION_COMPOSE_PROJECT:-simplematch-local-production-like}"
image_tag="${SIMPLEMATCH_LOCAL_IMAGE_TAG:-local}"
image_transport="${SIMPLEMATCH_LOCAL_IMAGE_TRANSPORT:-$SIMPLEMATCH_LOCAL_IMAGE_TRANSPORT_DEFAULT}"
evidence_dir="${SIMPLEMATCH_CERTIFICATION_EVIDENCE_DIR:-$repo_root/out/certification/local-production-like}"
image_lock="${SIMPLEMATCH_LOCAL_IMAGE_LOCK:-$evidence_dir/local-images.lock}"
matching_producer_config_file="${SIMPLEMATCH_KAFKA_PRODUCER_CONFIG_FILE:-$evidence_dir/matching-producer.config.txt}"
matching_capacity_evidence_file="${SIMPLEMATCH_KAFKA_CAPACITY_EVIDENCE_FILE:-$evidence_dir/kafka-capacity.properties}"
matching_capacity_workload_file="${SIMPLEMATCH_KAFKA_CAPACITY_WORKLOAD_FILE:-$repo_root/scripts/testdata/matching-topic-profile/local/capacity.properties}"
certification_trading_day="${SIMPLEMATCH_CERTIFICATION_TRADING_DAY:-$(TZ=Asia/Taipei date +%F)}"
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
kubernetes_job_evidence_interval_seconds="${SIMPLEMATCH_KUBERNETES_JOB_EVIDENCE_INTERVAL_SECONDS:-10}"
kafka_topic_provisioning_supervisor_seconds="${SIMPLEMATCH_KAFKA_TOPIC_PROVISIONING_SUPERVISOR_SECONDS:-270}"
certification_deadline_epoch=0
phase_marker_directory=""
run_context_file=""
source_signature=""
matching_image_reference=""
compose_prefix=()
compose_command=()

# Policy modules load before concrete artifact adapters. The generic artifact
# seam is loaded after image and Kafka adapters so Planner calls one interface
# without either adapter depending on the other.
for certification_lib in \
  local-certification-phase-graph.sh \
  local-certification-fingerprint.sh \
  local-certification-evidence.sh \
  local-certification-planner.sh \
  local-certification-images.sh \
  local-certification-kafka.sh \
  local-certification-artifacts.sh \
  local-certification-framework.sh \
  local-certification-job.sh \
  local-certification-kubernetes.sh \
  local-certification-connect.sh \
  local-certification-workloads.sh; do
  # shellcheck source=/dev/null
  source "$script_dir/lib/$certification_lib"
done
unset certification_lib

# Bootstrap validates configuration and runtime preconditions only. The run
# module maps phase IDs to adapters and the planner owns dependency order.
# shellcheck source=/dev/null
source "$script_dir/lib/local-certification-bootstrap.sh"
export SIMPLEMATCH_LOCAL_IMAGE_TRANSPORT="$image_transport"
# shellcheck source=/dev/null
source "$script_dir/lib/local-certification-run.sh"

certification_plan_finalize || die \
  'Certification plan does not have complete successful phase evidence.'
