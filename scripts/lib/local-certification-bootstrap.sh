#!/usr/bin/env bash

# Sourced by run-local-production-like-certification.sh. This file owns
# certification bootstrap/configuration but is not an independent entry point.

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
      resume=true
      shift
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
(( kafka_topic_provisioning_supervisor_seconds > 240 )) || die \
  'Kafka topic provisioning supervisor deadline must exceed the 240s Job deadline.'
if [[ "$dry_run" == false ]]; then
  command -v timeout >/dev/null 2>&1 || die 'timeout is required for bounded certification commands.'
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

run_id="$(date -u +%Y%m%d-%H%M%S)-$$"
namespace="${SIMPLEMATCH_CERTIFICATION_NAMESPACE:-simplematch-local-cert-${run_id}}"
phase_marker_directory="$evidence_dir/phases"
run_context_file="$evidence_dir/run-context"
certification_deadline_epoch=$(( $(date +%s) + certification_timeout_seconds ))
source_signature="$({
  git rev-parse HEAD
  git ls-files -co --exclude-standard -- \
    AGENTS.md deploy/k8s deploy/docker/run-flyway deploy/docker/Dockerfile.kind-normalized \
    scripts/run-local-production-like-certification.sh \
    scripts/lib/local-common.sh scripts/lib/local-kind.sh scripts/lib/local-image-transport.sh \
    scripts/lib/local-certification-framework.sh scripts/lib/local-certification-job.sh \
    scripts/lib/local-certification-kafka.sh scripts/lib/local-certification-kubernetes.sh \
    scripts/lib/local-certification-connect.sh scripts/lib/local-certification-workloads.sh \
    scripts/lib/local-certification-bootstrap.sh scripts/lib/local-certification-run.sh \
    scripts/prepare-local-kubernetes-images.sh scripts/normalize-local-images-for-kind.sh \
    scripts/publish-local-images.sh scripts/render-local-kubernetes-manifest.sh \
    scripts/test-kubernetes-overlays.sh scripts/test-local-kubernetes-dependencies.sh \
    scripts/test-postgresql-redis-manifests.sh scripts/test-flyway-services.sh |
    sort -u |
    while IFS= read -r path; do
      sha256sum "$path"
    done
} | sha256sum | awk '{print $1}')"

if [[ "$resume" == true ]]; then
  [[ -n "${SIMPLEMATCH_CERTIFICATION_EVIDENCE_DIR:-}" ]] || die \
    '--resume requires SIMPLEMATCH_CERTIFICATION_EVIDENCE_DIR.'
  [[ -n "${SIMPLEMATCH_CERTIFICATION_NAMESPACE:-}" ]] || die \
    '--resume requires SIMPLEMATCH_CERTIFICATION_NAMESPACE.'
  [[ -f "$run_context_file" ]] || die "Resume context is missing: $run_context_file"
  expected_context="$(printf 'namespace=%s\ncluster=%s\ntrading_day=%s\nimage_tag=%s\nimage_transport=%s\nsource_signature=%s\n' \
    "$namespace" "$kind_cluster" "$certification_trading_day" "$image_tag" "$image_transport" "$source_signature")"
  actual_context="$(cat "$run_context_file")"
  [[ "$actual_context" == "$expected_context" ]] || die \
    "Resume context does not match the current cluster, trading day, namespace, image tag, image transport, or source."
  if [[ "$dry_run" == false ]]; then
    kubectl --context "$kind_context" get namespace "$namespace" >/dev/null 2>&1 || die \
      "Resume namespace does not exist: $namespace"
    simplematch_kind_namespace_is_disposable \
      "$kind_context" "$namespace" local-production-like-certification || die \
      "Resume namespace is not owned as a disposable local certification namespace: $namespace"
  fi
  kubernetes_namespace_created=true
else
  if [[ "$dry_run" == false ]]; then
    mkdir -p "$evidence_dir"
    printf 'namespace=%s\ncluster=%s\ntrading_day=%s\nimage_tag=%s\nimage_transport=%s\nsource_signature=%s\n' \
      "$namespace" "$kind_cluster" "$certification_trading_day" "$image_tag" "$image_transport" "$source_signature" >"$run_context_file"
  fi
fi

if [[ "$skip_kubernetes" == false ]]; then
  if [[ "$dry_run" == false ]]; then
    command -v kubectl >/dev/null 2>&1 || die 'kubectl is required for the local Kubernetes gate.'
    command -v kind >/dev/null 2>&1 || die 'kind is required for the local Kubernetes gate; install kind or use --skip-kubernetes.'
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

run_logged static-kubernetes-overlays bash "$repo_root/scripts/test-kubernetes-overlays.sh"
run_logged static-kubernetes-dependencies bash "$repo_root/scripts/test-local-kubernetes-dependencies.sh"
run_logged static-matching-manifests bash "$repo_root/scripts/test-matching-kubernetes-manifests.sh"
run_logged static-matching-profile bash "$repo_root/scripts/test-matching-topic-profile.sh"
run_logged static-flyway-services bash "$repo_root/scripts/test-flyway-services.sh"
run_logged compose-config "${compose_command[@]}" config
run_logged local-image-inventory bash "$repo_root/scripts/build-local-images.sh" --list

if [[ "$skip_build" == false ]]; then
  run_logged local-image-build bash "$repo_root/scripts/build-local-images.sh" --tag "$image_tag"
fi
