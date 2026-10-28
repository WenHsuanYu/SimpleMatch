#!/usr/bin/env bash

# Sourced by run-local-production-like-certification.sh. This file owns
# certification bootstrap/configuration but does not execute certification
# phases and is not an independent entry point.

while [[ $# -gt 0 ]]; do
  case "$1" in
    --tag)
      image_tag="${2:?--tag requires a value}"
      shift 2
      ;;
    --image-transport)
      image_transport="${2:?--image-transport requires a value}"
      shift 2
      ;;
    --query-fixture-directory)
      query_fixture_directory="${2:?--query-fixture-directory requires a value}"
      shift 2
      ;;
    --skip-build)
      skip_build=true
      shift
      ;;
    --skip-compose)
      skip_compose=true
      shift
      ;;
    --skip-kubernetes)
      skip_kubernetes=true
      shift
      ;;
    --matching-fleet-only)
      matching_fleet_only=true
      shift
      ;;
    --keep-resources)
      keep_resources=true
      shift
      ;;
    --resume)
      die '--resume was retired. Start a new certification with a new evidence directory.'
      ;;
    --dry-run)
      dry_run=true
      shift
      ;;
    --help|-h)
      usage
      trap - EXIT
      exit 0
      ;;
    *)
      usage >&2
      die "Unknown option: $1"
      ;;
  esac
done

simplematch_local_image_transport_validate "$image_transport" || die \
  "SIMPLEMATCH_LOCAL_IMAGE_TRANSPORT/--image-transport must be registry or kind-load: $image_transport"
[[ "$certification_trading_day" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}$ ]] || die \
  "SIMPLEMATCH_CERTIFICATION_TRADING_DAY must use YYYY-MM-DD: $certification_trading_day"
[[ -f "$compose_file" ]] || die "Production-like Compose file does not exist: $compose_file"

[[ "$certification_timeout_seconds" =~ ^[1-9][0-9]*$ ]] || die \
  "SIMPLEMATCH_CERTIFICATION_TIMEOUT_SECONDS must be a positive integer: $certification_timeout_seconds"
[[ "$namespace_cleanup_timeout" =~ ^[1-9][0-9]*$ ]] || die \
  "SIMPLEMATCH_NAMESPACE_CLEANUP_TIMEOUT_SECONDS must be a positive integer: $namespace_cleanup_timeout"
[[ "$kubernetes_job_evidence_interval_seconds" =~ ^[1-9][0-9]*$ ]] || die \
  "SIMPLEMATCH_KUBERNETES_JOB_EVIDENCE_INTERVAL_SECONDS must be a positive integer: $kubernetes_job_evidence_interval_seconds"
[[ "$kafka_topic_provisioning_supervisor_seconds" =~ ^[1-9][0-9]*$ ]] || die \
  "SIMPLEMATCH_KAFKA_TOPIC_PROVISIONING_SUPERVISOR_SECONDS must be a positive integer: $kafka_topic_provisioning_supervisor_seconds"
(( kafka_topic_provisioning_supervisor_seconds > 600 )) || die \
  'Kafka topic provisioning supervisor deadline must exceed the 600s Job deadline.'
if [[ "$dry_run" == false ]]; then
  command -v timeout >/dev/null 2>&1 || die \
    'timeout is required for bounded certification commands.'
  command -v jq >/dev/null 2>&1 || die \
    'jq is required for certification plan and evidence records.'
fi

if [[ "$dry_run" == false && "$skip_kubernetes" == false && "$matching_fleet_only" == false ]]; then
  [[ -n "$query_fixture_directory" ]] || die \
    '--query-fixture-directory is required for secured full-local deployment.'
  query_security_input_digest="$(python3 \
    "$repo_root/scripts/end-to-end/query-service/lib/certification_security.py" \
    --fixture-directory "$query_fixture_directory" --fingerprint)" || die \
    'Private Query/FIX certification material is missing, invalid or expired.'
  query_security_deployed_digest="$(python3 \
    "$repo_root/scripts/end-to-end/query-service/lib/certification_security.py" \
    --fixture-directory "$query_fixture_directory" --deployed-fingerprint)" || die \
    'Cannot identify the server-side Query/FIX security snapshot.'
fi
query_security_deployed_digest="${query_security_deployed_digest:-not-required}"

# Reject retained evidence before daemon queries, trap installation, or report writes.
if [[ "$dry_run" == false && ( -e "$evidence_dir" || -L "$evidence_dir" ) ]]; then
  die "Certification evidence directory already exists: $evidence_dir. Select a new directory."
fi

if [[ "$dry_run" == true ]]; then
  compose_prefix=(docker compose)
elif command -v docker >/dev/null 2>&1 && docker compose version >/dev/null 2>&1; then
  compose_prefix=(docker compose)
elif command -v docker-compose >/dev/null 2>&1; then
  compose_prefix=(docker-compose)
else
  die 'Docker Compose v2 or docker-compose is required.'
fi
compose_command=("${compose_prefix[@]}" --project-name "$compose_project" --file "$compose_file")

namespace="${SIMPLEMATCH_CERTIFICATION_NAMESPACE:-simplematch-local-cert-${run_id}}"
run_context_file="$evidence_dir/run-context"
certification_deadline_epoch=$(( $(date +%s) + certification_timeout_seconds ))
source_signature="$(simplematch_certification_source_signature "$repo_root")"
cdc_runtime_signature="$(simplematch_certification_cdc_runtime_signature "$repo_root")"
cdc_verifier_signature="$(simplematch_certification_cdc_verifier_signature "$repo_root")"

_certification_run_context() {
  printf 'run_id=%s\nnamespace=%s\ncluster=%s\ntrading_day=%s\nimage_tag=%s\nimage_transport=%s\nsource_signature=%s\ncdc_runtime_signature=%s\ncdc_verifier_signature=%s\nskip_build=%s\nskip_compose=%s\nskip_kubernetes=%s\nmatching_fleet_only=%s\n' \
    "$run_id" "$namespace" "$kind_cluster" "$certification_trading_day" \
    "$image_tag" "$image_transport" "$source_signature" \
    "$cdc_runtime_signature" "$cdc_verifier_signature" \
    "$skip_build" "$skip_compose" "$skip_kubernetes" "$matching_fleet_only"
  printf 'query_security_input_digest=%s\n' "$query_security_input_digest"
  printf 'query_security_deployed_digest=%s\n' "$query_security_deployed_digest"
}

if [[ "$dry_run" == false ]]; then
  simplematch_docker_storage_preflight || die \
    'Docker host-capacity preflight failed; no production-like phase was started.'
  if [[ "$skip_compose" == false ]]; then
    existing_compose_containers="$("${compose_command[@]}" ps --all --quiet)" || \
      die 'Cannot inspect the selected Compose project.'
    [[ -z "$existing_compose_containers" ]] || \
      die 'The selected Compose project already has containers. Select an unused project.'
  fi
fi

if [[ "$skip_kubernetes" == false ]]; then
  if [[ "$dry_run" == false ]]; then
    command -v kubectl >/dev/null 2>&1 || die \
      'kubectl is required for the local Kubernetes gate.'
    command -v kind >/dev/null 2>&1 || die \
      'kind is required for the local Kubernetes gate; install kind or use --skip-kubernetes.'
    kind get clusters | grep -Fxq "$kind_cluster" || die \
      "kind cluster '$kind_cluster' does not exist; create it before running this gate."
    current_context="$(kubectl config current-context)"
    [[ "$current_context" == "$kind_context" ]] || die \
      "current Kubernetes context '$current_context' is not the canonical '$kind_context'."
    assert_certification_namespace_exclusive || die \
      'Local production-like certification namespace ownership is not exclusive.'
  fi
  export SIMPLEMATCH_PRODUCTION_LIKE_NETWORK="${SIMPLEMATCH_PRODUCTION_LIKE_NETWORK:-kind}"
  export SIMPLEMATCH_PRODUCTION_LIKE_NETWORK_EXTERNAL="${SIMPLEMATCH_PRODUCTION_LIKE_NETWORK_EXTERNAL:-true}"
else
  export SIMPLEMATCH_PRODUCTION_LIKE_NETWORK="${SIMPLEMATCH_PRODUCTION_LIKE_NETWORK:-simplematch-production-like}"
  export SIMPLEMATCH_PRODUCTION_LIKE_NETWORK_EXTERNAL="${SIMPLEMATCH_PRODUCTION_LIKE_NETWORK_EXTERNAL:-false}"
fi

certification_phase_validate_graph || die 'Certification phase dependencies are invalid.'
if [[ "$dry_run" == false ]]; then
  mkdir -p "$(dirname -- "$evidence_dir")" || die 'Cannot create the evidence parent directory.'
  mkdir -m 700 "$evidence_dir" || die 'Cannot reserve the new evidence directory.'
  _certification_run_context >"$run_context_file" || die 'Cannot write the certification run context.'
  git -C "$repo_root" rev-parse HEAD >"$evidence_dir/source-revision" || die 'Cannot record the source revision.'
fi
