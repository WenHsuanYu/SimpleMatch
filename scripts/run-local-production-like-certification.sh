#!/usr/bin/env bash

set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd -- "$script_dir/.." && pwd)"

compose_file="$repo_root/deploy/compose/kafka-connect.production-like.yml"
compose_project="${SIMPLEMATCH_CERTIFICATION_COMPOSE_PROJECT:-simplematch-local-production-like}"
image_tag="${SIMPLEMATCH_LOCAL_IMAGE_TAG:-local}"
evidence_dir="${SIMPLEMATCH_CERTIFICATION_EVIDENCE_DIR:-$repo_root/out/certification/local-production-like}"
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
certification_deadline_epoch=0
phase_marker_directory=""
run_context_file=""
source_signature=""
compose_prefix=()
compose_command=()

usage() {
  cat <<'EOF'
Usage:
  scripts/run-local-production-like-certification.sh [options]

Options:
  --tag TAG               Local image tag (default: SIMPLEMATCH_LOCAL_IMAGE_TAG or local).
  --skip-build            Reuse local images instead of running the image build workflow.
  --skip-compose          Skip PostgreSQL, Redis, Kafka, and Kafka Connect runtime checks.
  --skip-kubernetes        Skip the live Kubernetes deployment and Matching fleet checks.
  --matching-fleet-only    Run a clean local Kafka plus Matching fleet gate; skip Flyway and other
                           runtime workloads. The report is intentionally PARTIAL.
  --keep-resources         Keep only this run's Compose project and Kubernetes namespace.
  --resume                 Reuse successful phases from the supplied evidence directory and
                           namespace; requires SIMPLEMATCH_CERTIFICATION_EVIDENCE_DIR and
                           SIMPLEMATCH_CERTIFICATION_NAMESPACE.
  --dry-run                Print planned commands without changing external state.
  --help                   Show this help.

The local gate owns only the Compose project named by
SIMPLEMATCH_CERTIFICATION_COMPOSE_PROJECT and a generated Kubernetes namespace.
It never pushes images and never touches staging or production resources.
The Kubernetes gate uses the approved delivery manifest for
SIMPLEMATCH_CERTIFICATION_TRADING_DAY (default: current UTC day) under
tools/market-reference-builder/data, or the path supplied by
SIMPLEMATCH_MARKET_REFERENCE_DELIVERY_MANIFEST.
The Kafka profile gate generates source-backed producer evidence and measures free Docker
filesystem capacity for the local brokers. The default workload envelope is the bounded local
side-project scenario under scripts/testdata/matching-topic-profile/local/capacity.properties;
override it with SIMPLEMATCH_KAFKA_CAPACITY_WORKLOAD_FILE when a different explicitly documented
workload envelope is required. Override producer or capacity evidence with
SIMPLEMATCH_KAFKA_PRODUCER_CONFIG_FILE or SIMPLEMATCH_KAFKA_CAPACITY_EVIDENCE_FILE.
EOF
}

die() {
  failure_reason="$*"
  printf '%s\n' "$*" >&2
  exit 1
}

print_command() {
  printf 'DRY RUN:'
  printf ' %q' "$@"
  printf '\n'
}

run_logged() {
  local phase="$1"
  shift
  local log_path="$evidence_dir/${phase}.log"
  local marker_path="$phase_marker_directory/${phase}.ok"
  local phase_signature

  phase_signature="$(printf '%s\0' "$source_signature" "$phase" "$@" | sha256sum | awk '{print $1}')"

  if [[ "$resume" == true && -f "$marker_path" && "$(<"$marker_path")" == "$phase_signature" ]]; then
    completed_phases+=("$phase (reused)")
    printf 'REUSE %-32s (%s)\n' "$phase" "$marker_path"
    return 0
  fi

  check_certification_deadline

  if [[ "$dry_run" == true ]]; then
    print_command "$@"
    return 0
  fi

  mkdir -p "$evidence_dir"
  printf '$' >"$log_path"
  printf ' %q' "$@" >>"$log_path"
  printf '\n' >>"$log_path"
  local command_status
  set +e
  (
    set -e
    execute_with_certification_deadline "$@"
  ) >>"$log_path" 2>&1
  command_status=$?
  set -e
  if [[ "$command_status" -eq 0 ]]; then
    mkdir -p "$phase_marker_directory"
    printf '%s\n' "$phase_signature" >"$marker_path"
    completed_phases+=("$phase")
    printf 'PASS %-32s (%s)\n' "$phase" "$log_path"
    return 0
  fi

  failed_phase="$phase"
  failure_reason="Phase failed: $phase"
  cat "$log_path" >&2
  return "$command_status"
}

run_capture() {
  local phase="$1"
  local output_path="$2"
  shift 2
  local marker_path="$phase_marker_directory/${phase}.ok"
  local phase_signature

  phase_signature="$(printf '%s\0' "$source_signature" "$phase" "$output_path" "$@" | sha256sum | awk '{print $1}')"

  if [[ "$resume" == true && -f "$marker_path" && "$(<"$marker_path")" == "$phase_signature" ]]; then
    completed_phases+=("$phase (reused)")
    printf 'REUSE %-32s (%s)\n' "$phase" "$output_path"
    return 0
  fi

  check_certification_deadline

  if [[ "$dry_run" == true ]]; then
    print_command "$@"
    return 0
  fi

  mkdir -p "$(dirname -- "$output_path")" "$evidence_dir"
  local command_status
  set +e
  (
    set -e
    execute_with_certification_deadline "$@"
  ) >"$output_path" 2>&1
  command_status=$?
  set -e
  if [[ "$command_status" -eq 0 ]]; then
    mkdir -p "$phase_marker_directory"
    printf '%s\n' "$phase_signature" >"$marker_path"
    completed_phases+=("$phase")
    printf 'PASS %-32s (%s)\n' "$phase" "$output_path"
    return 0
  fi

  failed_phase="$phase"
  failure_reason="Phase failed: $phase"
  cat "$output_path" >&2
  return "$command_status"
}

check_certification_deadline() {
  local now remaining
  if [[ "$certification_deadline_epoch" -eq 0 ]]; then
    return 0
  fi
  now="$(date +%s)"
  remaining=$((certification_deadline_epoch - now))
  if (( remaining <= 0 )); then
    die "Certification exceeded SIMPLEMATCH_CERTIFICATION_TIMEOUT_SECONDS=${certification_timeout_seconds}."
  fi
}

certification_deadline_remaining() {
  local now remaining
  if [[ "$certification_deadline_epoch" -eq 0 ]]; then
    printf '%s\n' 0
    return 0
  fi
  now="$(date +%s)"
  remaining=$((certification_deadline_epoch - now))
  (( remaining > 0 )) || return 1
  printf '%s\n' "$remaining"
}

execute_with_certification_deadline() {
  local remaining
  check_certification_deadline
  if declare -F "$1" >/dev/null 2>&1; then
    "$@"
    return
  fi
  remaining="$(certification_deadline_remaining)" || die \
    "Certification deadline expired while starting $1."
  timeout --foreground "${remaining}s" "$@"
}

write_report() {
  local exit_code="$1"
  [[ "$dry_run" == true ]] && return 0

  mkdir -p "$evidence_dir"
  if [[ "$exit_code" -ne 0 ]]; then
    completion_status="FAILED"
  elif [[ "$skip_build" == true || "$skip_compose" == true || "$skip_kubernetes" == true || "$matching_fleet_only" == true ]]; then
    completion_status="PARTIAL"
    failure_reason="One or more certification phases were explicitly skipped."
  else
    completion_status="PASSED"
  fi

  {
    printf '%s\n\n' '# SimpleMatch local production-like certification'
    printf '%s\n' "- status: $completion_status"
    printf '%s\n' "- generated_at_utc: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
    printf '%s\n' "- local_image_tag: $image_tag"
    printf '%s\n' "- compose_project: $compose_project"
    printf '%s\n' "- compose_file: ${compose_file#$repo_root/}"
    printf '%s\n' "- kubernetes_namespace: ${namespace:-not-run}"
    printf '%s\n' "- trading_day: $certification_trading_day"
    printf '%s\n' "- gradle: 9.7.0"
    printf '%s\n' "- spring_boot: 4.1.0"
    printf '%s\n' "- vcpkg: 2026.07.29"
    printf '%s\n' "- matching_base: ubuntu:26.04"
    printf '%s\n' "- kafka: 4.3.1"
    printf '%s\n' "- postgresql: 18.4"
    printf '%s\n' "- redis: 8.8.1-alpine"
    printf '%s\n' "- debezium: 3.6.0.Final"
    if [[ -n "$failed_phase" ]]; then
      printf '%s\n' "- failed_phase: $failed_phase"
    fi
    if [[ -n "$failure_reason" ]]; then
      printf '%s\n' "- note: $failure_reason"
    fi
    printf '\n%s\n\n' '## Completed phases'
    if [[ ${#completed_phases[@]} -eq 0 ]]; then
      printf '%s\n' '- none'
    else
      printf '%s\n' "${completed_phases[@]}" | sed 's/^/- /'
    fi
  } >"$evidence_dir/report.md"
}

cleanup() {
  local exit_code="$?"

  if [[ "$dry_run" == false && "$keep_resources" == false ]]; then
    if [[ "$compose_started" == true ]]; then
      "${compose_command[@]}" down --volumes --remove-orphans >/dev/null 2>&1 || true
    fi
    if [[ "$kubernetes_namespace_created" == true && -n "$namespace" ]]; then
      kubectl delete namespace "$namespace" --ignore-not-found >/dev/null 2>&1 || true
    fi
  fi

  write_report "$exit_code"
  trap - EXIT
  exit "$exit_code"
}
trap cleanup EXIT

while [[ $# -gt 0 ]]; do
  case "$1" in
    --tag)
      image_tag="${2:?--tag requires a value}"
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

[[ "$certification_trading_day" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}$ ]] || die \
  "SIMPLEMATCH_CERTIFICATION_TRADING_DAY must use YYYY-MM-DD: $certification_trading_day"

[[ -f "$compose_file" ]] || die "Production-like Compose file does not exist: $compose_file"

[[ "$certification_timeout_seconds" =~ ^[1-9][0-9]*$ ]] || die \
  "SIMPLEMATCH_CERTIFICATION_TIMEOUT_SECONDS must be a positive integer: $certification_timeout_seconds"
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
    AGENTS.md deploy/k8s deploy/docker/run-flyway scripts/run-local-production-like-certification.sh \
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
  expected_context="$(printf 'namespace=%s\ncluster=%s\ntrading_day=%s\nsource_signature=%s\n' \
    "$namespace" "$kind_cluster" "$certification_trading_day" "$source_signature")"
  actual_context="$(cat "$run_context_file")"
  [[ "$actual_context" == "$expected_context" ]] || die \
    "Resume context does not match the current cluster, trading day, namespace, or source."
  [[ "$dry_run" == true ]] || kubectl get namespace "$namespace" >/dev/null 2>&1 || die \
    "Resume namespace does not exist: $namespace"
  kubernetes_namespace_created=true
else
  if [[ "$dry_run" == false ]]; then
    mkdir -p "$evidence_dir"
    printf 'namespace=%s\ncluster=%s\ntrading_day=%s\nsource_signature=%s\n' \
      "$namespace" "$kind_cluster" "$certification_trading_day" "$source_signature" >"$run_context_file"
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

wait_for_compose() {
  local services service container_id state health ready attempt
  mapfile -t services < <("${compose_command[@]}" config --services)
  for attempt in $(seq 1 90); do
    check_certification_deadline
    ready=true
    for service in "${services[@]}"; do
      container_id="$("${compose_command[@]}" ps -q "$service")"
      if [[ -z "$container_id" ]]; then
        ready=false
        printf '%s is not created yet\n' "$service"
        continue
      fi
      state="$(docker inspect --format '{{.State.Status}}' "$container_id")"
      health="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$container_id")"
      printf '%s state=%s health=%s\n' "$service" "$state" "$health"
      if [[ "$state" != running || ( "$health" != healthy && "$health" != none ) ]]; then
        ready=false
      fi
    done
    if [[ "$ready" == true ]]; then
      printf '%s\n' 'All production-like Compose services are ready.'
      return 0
    fi
    sleep 2
  done
  die 'Production-like Compose services did not become ready within 180 seconds.'
}

create_kafka_topics() {
  local topic
  for topic in matching.commands matching.events account.lifecycle marketdata.events; do
    run_logged "kafka-create-${topic//./-}" "${compose_command[@]}" exec -T kafka-1 \
      /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka-1:29092 --create --if-not-exists \
      --topic "$topic" --partitions 15 --replication-factor 3 \
      --config cleanup.policy=delete --config retention.ms=2592000000 --config min.insync.replicas=2
  done
}

generate_kafka_capacity_evidence() {
  local source_file="$matching_capacity_workload_file"
  local output_file="$matching_capacity_evidence_file"
  local service free_kib free_bytes
  local minimum_free_bytes=""
  local broker_count=3

  [[ -f "${source_file}" ]] || die "Kafka workload scenario does not exist: ${source_file}"
  if [[ -n "${SIMPLEMATCH_KAFKA_CAPACITY_EVIDENCE_FILE:-}" ]]; then
    [[ -f "${output_file}" ]] || die "Kafka capacity evidence does not exist: ${output_file}"
    printf 'Using supplied Kafka capacity evidence: %s\n' "${output_file}"
    return 0
  fi

  mkdir -p "$(dirname -- "${output_file}")"
  for service in kafka-1 kafka-2 kafka-3; do
    free_kib="$("${compose_command[@]}" exec -T "${service}" sh -c \
      'df -Pk / | tail -n 1' | awk 'NR == 1 { print $4 }' | tr -d '\r')"
    [[ "${free_kib}" =~ ^[0-9]+$ ]] || die \
      "Kafka broker ${service} reported invalid free filesystem blocks: ${free_kib}"
    free_bytes="$(awk -v kib="${free_kib}" 'BEGIN { printf "%.0f", kib * 1024 }')"
    if [[ -z "${minimum_free_bytes}" || "${free_bytes}" -lt "${minimum_free_bytes}" ]]; then
      minimum_free_bytes="${free_bytes}"
    fi
  done

  {
    awk -F= '/^(workload\.commands\.per\.day|workload\.events\.per\.day|workload\.average\.command\.record\.bytes|workload\.average\.event\.record\.bytes)=/ { print }' \
      "${source_file}"
    printf '%s\n' "capacity.broker.count=${broker_count}"
    printf '%s\n' "capacity.usable.cluster.bytes=${minimum_free_bytes}"
    printf '%s\n' "capacity.usable.broker.bytes=$((minimum_free_bytes / broker_count))"
    printf '%s\n' 'capacity.evidence.source=runtime-docker-filesystem'
    printf '%s\n' 'capacity.evidence.path=/'
    printf '%s\n' "capacity.evidence.generated_at_utc=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  } >"${output_file}"
  printf 'Generated runtime Kafka capacity evidence: %s\n' "${output_file}"
}

collect_kafka_fixture() {
  local fixture_dir="$evidence_dir/kafka-fixture"
  local topic
  mkdir -p "$fixture_dir"
  for topic in matching.commands matching.events; do
    run_capture "kafka-describe-${topic//./-}" "$fixture_dir/${topic}.topic.txt" \
      "${compose_command[@]}" exec -T kafka-1 /opt/kafka/bin/kafka-topics.sh \
      --bootstrap-server kafka-1:29092 --describe --topic "$topic"
    run_capture "kafka-config-${topic//./-}" "$fixture_dir/${topic}.config.txt" \
      "${compose_command[@]}" exec -T kafka-1 /opt/kafka/bin/kafka-configs.sh \
      --bootstrap-server kafka-1:29092 --entity-type topics --entity-name "$topic" --describe
  done
  run_capture kafka-broker-config "$fixture_dir/broker.config.txt" \
    "${compose_command[@]}" exec -T kafka-1 cat /opt/kafka/config/server.properties
  run_logged kafka-profile-validation bash "$repo_root/scripts/validate-matching-topic-profile.sh" \
    --profile production --fixture-dir "$fixture_dir" \
    --producer-config-file "$matching_producer_config_file" \
    --capacity-evidence-file "$matching_capacity_evidence_file" --certify-production
}

render_local_kubernetes_manifest() {
  local rendered_manifest="$evidence_dir/local-kubernetes.yaml"
  mkdir -p "$evidence_dir"
  if [[ "$dry_run" == true ]]; then
    print_command kubectl kustomize "$repo_root/deploy/k8s/overlays/local" --load-restrictor LoadRestrictionsNone
    return 0
  fi
  kubectl kustomize "$repo_root/deploy/k8s/overlays/local" --load-restrictor LoadRestrictionsNone >"$rendered_manifest"
  sed -i -e "s/namespace: simplematch-local$/namespace: ${namespace}/g" "$rendered_manifest"
  printf '%s\n' "$rendered_manifest"
}

split_kubernetes_manifest() {
  local rendered_manifest="$1"
  local platform_manifest="$2"
  local migration_manifest="$3"
  local workload_manifest="$4"
  local input_manifest="$5"

ruby - "$rendered_manifest" "$platform_manifest" "$migration_manifest" "$workload_manifest" "$input_manifest" <<'RUBY'
require "yaml"
require "psych"

rendered_manifest, platform_manifest, migration_manifest, workload_manifest, input_manifest = ARGV
visitor = Psych::Visitors::ToRuby.create
documents = Psych.parse_stream(File.read(rendered_manifest)).children.map { |document| visitor.accept(document) }.compact

buckets = {
  platform_manifest => [],
  migration_manifest => [],
  workload_manifest => [],
  input_manifest => []
}

documents.each do |document|
  target = case document.fetch("kind")
           when "ConfigMap"
             document.fetch("metadata").fetch("name") == "quickfix-gateway-fix-spec" ? input_manifest : platform_manifest
           when "Job"
             migration_manifest
           when "StatefulSet"
             %w[postgres kafka].include?(document.dig("metadata", "name")) ? platform_manifest : workload_manifest
           when "Deployment"
             document.dig("metadata", "name") == "redis" ? platform_manifest : workload_manifest
           else
             platform_manifest
           end
  buckets.fetch(target) << document
end

buckets.each do |path, bucket|
  File.write(path, YAML.dump_stream(*bucket))
end
RUBY
}

apply_local_kubernetes_inputs() {
  local matching_digest="$1"
  local input_manifest="$2"
  local trading_day="$certification_trading_day"
  local service
  local delivery_manifest
  local artifact_manifest
  local manifest_name
  delivery_manifest="${SIMPLEMATCH_MARKET_REFERENCE_DELIVERY_MANIFEST:-$repo_root/tools/market-reference-builder/data/${trading_day}/delivery/manifest.yaml}"
  artifact_manifest="$evidence_dir/matching-daily-artifact.yaml"

  [[ -f "$delivery_manifest" ]] || die \
    "Approved Market Reference delivery manifest does not exist: $delivery_manifest"
  manifest_name="$(awk '/^  name: market-reference-/ { print $2; exit }' "$delivery_manifest")"
  [[ "$manifest_name" == "market-reference-${trading_day//-/}"-* ]] || die \
    "Approved Market Reference delivery manifest is for ${manifest_name:-an unknown day}; expected $trading_day"
  mkdir -p "$evidence_dir"
  awk '
    /^---$/ { exit }
    /^  name: market-reference-/ {
      sub(/^  name: market-reference-[^[:space:]]+$/, "  name: matching-daily-artifact")
    }
    { print }
  ' "$delivery_manifest" >"$artifact_manifest"
  grep -Fxq '  name: matching-daily-artifact' "$artifact_manifest" || die \
    "Approved Market Reference delivery manifest has no ConfigMap name"
  grep -Fxq 'immutable: true' "$artifact_manifest" || die \
    "Approved Market Reference ConfigMap must be immutable"

  if ! kubectl -n "$namespace" create -f "$artifact_manifest" >/dev/null; then
    printf 'Failed to create the immutable Market Reference artifact ConfigMap.\n' >&2
    return 1
  fi
  if ! kubectl create -f "$input_manifest" >/dev/null; then
    printf 'Failed to create the immutable QuickFIX FIX44 dictionary ConfigMap.\n' >&2
    return 1
  fi

  kubectl -n "$namespace" create configmap matching-session-config \
    --from-literal="trading_session_id=${trading_day}-regular" \
    --from-literal="trading_day=${trading_day}" \
    --from-literal="matching_image_digest=${matching_digest}" \
    --dry-run=client -o yaml | kubectl apply -f - >/dev/null

  kubectl -n "$namespace" create secret generic simplematch-flyway-secrets \
    --from-literal=postgres_dsn="$local_postgres_dsn" \
    --dry-run=client -o yaml | kubectl apply -f - >/dev/null

  kubectl -n "$namespace" create secret generic simplematch-postgres-secrets \
    --from-literal=postgres_user=simplematch \
    --from-literal=postgres_password="$local_postgres_password" \
    --dry-run=client -o yaml | kubectl apply -f - >/dev/null

  for service in account-service risk-service persistence market-data-projection marketdata-publisher query-service quickfix-gateway; do
    kubectl -n "$namespace" create secret generic "${service}-secrets" \
      --from-literal=postgres_dsn="$local_postgres_dsn" \
      --dry-run=client -o yaml | kubectl apply -f - >/dev/null
  done
}

create_certification_namespace() {
  if kubectl get namespace "$namespace" >/dev/null 2>&1; then
    die "Certification namespace already exists: $namespace"
  fi

  kubectl create namespace "$namespace" >/dev/null
  kubernetes_namespace_created=true
}

publish_local_matching_open_barriers() {
  local matching_digest="$1"
  local delivery_manifest
  local artifact_root
  local artifact_json
  local artifact_sha256
  local routing_version
  local fixture_publisher="$repo_root/out/build/full-native-dev/simplematch-matching-kafka-fixture-publisher"
  local fixture_pod="matching-fixture-publisher"

  delivery_manifest="${SIMPLEMATCH_MARKET_REFERENCE_DELIVERY_MANIFEST:-$repo_root/tools/market-reference-builder/data/${certification_trading_day}/delivery/manifest.yaml}"
  artifact_root="$(cd -- "$(dirname -- "$delivery_manifest")/.." && pwd)"
  artifact_json="$artifact_root/market_reference.json"
  [[ -f "$artifact_json" ]] || die "Approved Market Reference JSON does not exist: $artifact_json"
  artifact_sha256="$(tr -d '[:space:]' <"$artifact_root/market_reference.sha256")"
  [[ "$artifact_sha256" =~ ^[0-9a-f]{64}$ ]] || die \
    "Approved Market Reference checksum is not a canonical sha256: $artifact_root/market_reference.sha256"
  routing_version="$(ruby -rjson -e 'puts JSON.parse(File.read(ARGV.fetch(0))).fetch("metadata").fetch("routingAlgorithmVersion")' "$artifact_json")"
  [[ -n "$routing_version" ]] || die 'Approved Market Reference routing algorithm version is missing.'

  if [[ ! -x "$fixture_publisher" ]]; then
    cmake --preset full-native-dev
  fi
  cmake --build --preset full-native-dev --target simplematch-matching-kafka-fixture-publisher --parallel
  kubectl -n "$namespace" wait \
    --for=jsonpath='{.status.readyReplicas}'=3 statefulset/kafka --timeout=300s
  kubectl -n "$namespace" wait --for=condition=complete \
    job/kafka-topic-provisioning --timeout=300s
  kubectl -n "$namespace" delete pod "$fixture_pod" --ignore-not-found --wait=true >/dev/null
  kubectl -n "$namespace" run "$fixture_pod" \
    --image=simplematch-matching:local --image-pull-policy=IfNotPresent \
    --restart=Never --command -- sleep 300 >/dev/null
  kubectl -n "$namespace" wait --for=condition=Ready "pod/$fixture_pod" --timeout=120s
  base64 "$fixture_publisher" | kubectl -n "$namespace" exec -i "$fixture_pod" -- \
    sh -c 'base64 -d >/tmp/simplematch-matching-kafka-fixture-publisher && chmod 755 /tmp/simplematch-matching-kafka-fixture-publisher'
  kubectl -n "$namespace" exec "$fixture_pod" -- \
    /tmp/simplematch-matching-kafka-fixture-publisher kafka:9092 matching.commands \
    "$certification_trading_day" "${certification_trading_day}-regular" "$artifact_sha256" \
    "$routing_version" "$matching_digest"
  kubectl -n "$namespace" delete pod "$fixture_pod" --ignore-not-found --wait=true >/dev/null
}

apply_kubernetes_migrations() {
  local migration_manifest="$1"
  local workload
  apply_kubernetes_topic_provisioning "$migration_manifest"
  for workload in account-service risk-service persistence market-data-projection marketdata-publisher query-service quickfix-gateway; do
    kubectl apply -f "$migration_manifest" \
      --selector "app.kubernetes.io/name=${workload}-flyway"
    kubectl -n "$namespace" wait --for=condition=complete \
      "job/${workload}-flyway" --timeout=300s
  done
}

apply_kubernetes_topic_provisioning() {
  local migration_manifest="$1"
  kubectl -n "$namespace" wait \
    --for=jsonpath='{.status.readyReplicas}'=3 statefulset/kafka --timeout=300s
  kubectl apply -f "$migration_manifest" \
    --selector 'app.kubernetes.io/component=topic-provisioning'
  kubectl -n "$namespace" wait --for=condition=complete \
    job/kafka-topic-provisioning --timeout=300s
}

register_kubernetes_risk_connector() (
  local requested_forward_port="${SIMPLEMATCH_KAFKA_CONNECT_FORWARD_PORT:-}"
  local forward_port=""
  local connector_url=""
  local connector_json="$evidence_dir/risk-service-outbox-connector.json"
  local connector_config="$evidence_dir/risk-service-outbox-connector-config.json"
  local response_file="$evidence_dir/risk-service-outbox-connector-response.json"
  local update_response_file="$evidence_dir/risk-service-outbox-connector-update-response.json"
  local status_file="$evidence_dir/risk-service-outbox-status.json"
  local connectors_file="$evidence_dir/kafka-connect-connectors.json"
  local port_forward_log="$evidence_dir/kafka-connect-port-forward.log"
  local port_forward_pid
  local status_code
  local provider_retry_count=0
  local provider_retry_limit="${SIMPLEMATCH_KAFKA_CONNECT_PROVIDER_RETRIES:-90}"
  local connect_rest_ready=false
  local -a curl_options=(--connect-timeout 5 --max-time 15)

  request_to_file() {
    local method="$1"
    local path="$2"
    local output_file="$3"
    local payload_file="${4:-}"
    local -a request=(curl "${curl_options[@]}" -sS -o "$output_file" -w '%{http_code}' -X "$method")

    if [[ -n "$payload_file" ]]; then
      request+=(-H 'Content-Type: application/json' --data-binary "@$payload_file")
    fi
    "${request[@]}" "${connector_url}${path}"
  }

  print_evidence() {
    local label="$1"
    local path="$2"
    printf '\n=== %s ===\n' "$label" >&2
    if [[ ! -s "$path" ]]; then
      printf '(empty)\n' >&2
      return 0
    fi
    jq . "$path" >&2 2>/dev/null || cat "$path" >&2
    printf '\n' >&2
  }

  dump_connect_diagnostics() {
    request_to_file GET /connectors "$connectors_file" >/dev/null 2>&1 || true
    request_to_file GET /connectors/risk-service-outbox/status "$status_file" >/dev/null 2>&1 || true
    print_evidence 'Kafka Connect connectors' "$connectors_file"
    print_evidence 'risk-service-outbox registration response' "$response_file"
    print_evidence 'risk-service-outbox update response' "$update_response_file"
    print_evidence 'risk-service-outbox status' "$status_file"
    printf '\n=== Kafka Connect port-forward ===\n' >&2
    cat "$port_forward_log" >&2 || true
  }

  status_response_is_valid() {
    jq -e '
      (.connector.state | type == "string")
      and (.tasks | type == "array")
    ' "$status_file" >/dev/null 2>&1
  }

  status_response_has_failure() {
    jq -e '
      (.connector.state == "FAILED")
      or any(.tasks[]?; .state == "FAILED")
    ' "$status_file" >/dev/null 2>&1
  }

  status_response_is_running() {
    jq -e '
      .connector.state == "RUNNING"
      and (.tasks | length > 0)
      and all(.tasks[]; .state == "RUNNING")
    ' "$status_file" >/dev/null 2>&1
  }

  fail_with_diagnostics() {
    printf '%s\n' "$1" >&2
    dump_connect_diagnostics
    exit 1
  }

  [[ "$provider_retry_limit" =~ ^[1-9][0-9]*$ ]] || {
    printf 'SIMPLEMATCH_KAFKA_CONNECT_PROVIDER_RETRIES must be a positive integer.\n' >&2
    exit 1
  }

  kubectl -n "$namespace" rollout status deployment/kafka-connect --timeout=300s
  kubectl -n "$namespace" get configmap risk-service-outbox-connector \
    -o jsonpath='{.data.connector\.json}' >"$connector_json"
  jq -e '.name == "risk-service-outbox" and (.config | type == "object")' "$connector_json" >/dev/null

  local forward_spec="${requested_forward_port}:8083"
  kubectl -n "$namespace" port-forward service/kafka-connect "$forward_spec" \
    >"$port_forward_log" 2>&1 &
  port_forward_pid=$!
  stop_port_forward() {
    kill "$port_forward_pid" >/dev/null 2>&1 || true
    wait "$port_forward_pid" >/dev/null 2>&1 || true
  }
  trap stop_port_forward EXIT

  for _ in $(seq 1 30); do
    check_certification_deadline
    if [[ -s "$port_forward_log" ]]; then
      if grep -Eq 'Unable to listen|error: unable to listen|address already in use' \
        "$port_forward_log"; then
        cat "$port_forward_log" >&2
        exit 1
      fi
      forward_port="$(sed -nE 's/.*Forwarding from 127\.0\.0\.1:([0-9]+).*/\1/p' \
        "$port_forward_log" | tail -1)"
      if [[ -n "$forward_port" ]]; then
        break
      fi
    fi
    if ! kill -0 "$port_forward_pid" >/dev/null 2>&1; then
      cat "$port_forward_log" >&2
      exit 1
    fi
    sleep 1
  done
  [[ -n "$forward_port" ]] || {
    cat "$port_forward_log" >&2
    exit 1
  }
  connector_url="http://127.0.0.1:${forward_port}"

  for _ in $(seq 1 90); do
    check_certification_deadline
    if status_code="$(request_to_file GET /connectors "$connectors_file" 2>/dev/null)" \
      && [[ "$status_code" == 2?? ]]; then
      connect_rest_ready=true
      break
    fi
    if ! kill -0 "$port_forward_pid" >/dev/null 2>&1; then
      cat "$port_forward_log" >&2
      exit 1
    fi
    sleep 2
  done
  if [[ "$connect_rest_ready" != true ]]; then
    fail_with_diagnostics 'Kafka Connect REST endpoint did not become ready before registration.'
  fi

  while true; do
    check_certification_deadline
    if ! status_code="$(request_to_file POST /connectors "$response_file" "$connector_json")"; then
      fail_with_diagnostics 'Kafka Connect REST registration request failed before receiving a response.'
    fi
    case "$status_code" in
      2??)
        break
        ;;
      409)
        jq -c '.config' "$connector_json" >"$connector_config"
        if ! status_code="$(request_to_file PUT /connectors/risk-service-outbox/config \
          "$update_response_file" "$connector_config")"; then
          fail_with_diagnostics 'Kafka Connect REST update request failed before receiving a response.'
        fi
        [[ "$status_code" == 2?? ]] || fail_with_diagnostics \
          "Kafka Connect rejected risk-service-outbox update with HTTP ${status_code}."
        break
        ;;
      400)
        if ! jq -e '
          (.message // "")
          | test("\\$\\{envvarprovider:[A-Za-z0-9_]+\\}")
        ' "$response_file" >/dev/null 2>&1; then
          fail_with_diagnostics 'Kafka Connect rejected risk-service-outbox registration with HTTP 400.'
        fi
        provider_retry_count=$((provider_retry_count + 1))
        if (( provider_retry_count >= provider_retry_limit )); then
          fail_with_diagnostics \
            "Kafka Connect EnvVarConfigProvider remained unavailable after ${provider_retry_limit} attempts."
        fi
        printf 'Kafka Connect EnvVarConfigProvider is not ready; retrying registration (%d/%d).\n' \
          "$provider_retry_count" "$provider_retry_limit" >&2
        sleep 2
        ;;
      *)
        fail_with_diagnostics \
          "Kafka Connect rejected risk-service-outbox registration with HTTP ${status_code}."
        ;;
    esac
  done

  for _ in $(seq 1 90); do
    check_certification_deadline
    if ! status_code="$(request_to_file GET /connectors/risk-service-outbox/status "$status_file")"; then
      fail_with_diagnostics 'Kafka Connect status request failed before receiving a response.'
    fi

    case "$status_code" in
      200)
        status_response_is_valid || fail_with_diagnostics \
          'Kafka Connect returned a malformed risk-service-outbox status response.'
        status_response_has_failure && fail_with_diagnostics \
          'risk-service-outbox entered FAILED state.'
        if status_response_is_running; then
          exit 0
        fi
        ;;
      404)
        # A newly registered connector can be briefly absent from the REST view while the
        # distributed workers publish and consume the config update. Treat this as transient.
        ;;
      *)
        fail_with_diagnostics \
          "Kafka Connect returned unexpected HTTP ${status_code} for risk-service-outbox status."
        ;;
    esac
    sleep 2
  done

  fail_with_diagnostics 'risk-service-outbox did not reach RUNNING state before the status deadline.'
)

select_matching_workload() {
  local workload_manifest="$1"
  local matching_manifest="$2"

  ruby -ryaml -e '
    documents = YAML.load_stream(File.read(ARGV.fetch(0))).compact
    matching = documents.select do |document|
      document.fetch("kind") == "StatefulSet" && document.dig("metadata", "name") == "matching"
    end
    abort "rendered local workload manifest must contain exactly one Matching StatefulSet" unless matching.length == 1
    File.write(ARGV.fetch(1), YAML.dump_stream(*matching))
  ' "$workload_manifest" "$matching_manifest"
}

wait_for_kubernetes_workloads() {
  local workload
  for workload in account-service risk-service persistence market-data-projection marketdata-publisher marketdata-streamer query-service; do
    check_certification_deadline
    kubectl -n "$namespace" rollout status "deployment/${workload}" --timeout=300s
  done
  check_certification_deadline
  kubectl -n "$namespace" rollout status statefulset/matching --timeout=600s
  check_certification_deadline
  kubectl -n "$namespace" rollout status statefulset/quickfix-gateway --timeout=300s
}

wait_for_local_matching_fleet() {
  check_certification_deadline
  kubectl -n "$namespace" rollout status statefulset/matching --timeout=600s
}

if [[ "$skip_compose" == false ]]; then
  compose_started=true
  run_logged compose-up "${compose_command[@]}" up --detach --remove-orphans
  run_logged compose-wait wait_for_compose
  run_logged compose-status "${compose_command[@]}" ps
  run_logged kafka-capacity-evidence generate_kafka_capacity_evidence
  if [[ -z "${SIMPLEMATCH_KAFKA_PRODUCER_CONFIG_FILE:-}" ]]; then
    run_logged kafka-producer-contract bash \
      "$repo_root/scripts/validate-matching-producer-contract.sh" \
      --output "$matching_producer_config_file"
  fi
  create_kafka_topics
  collect_kafka_fixture
  run_logged kafka-broker-failure-live bash \
    "$repo_root/scripts/run-matching-kafka-failure-check.sh" \
    --compose-project "$compose_project" --compose-file "$compose_file" \
    --evidence-dir "$evidence_dir/kafka-failure" \
    --producer-config-file "$matching_producer_config_file" \
    --capacity-evidence-file "$matching_capacity_evidence_file"
  if [[ "$matching_fleet_only" == false ]]; then
    printf '%s\n' 'Compose Flyway phase omitted; Kubernetes Flyway Jobs own the local schema.'
  else
    printf '%s\n' 'Compose Flyway phases skipped for the Matching fleet-only gate.'
  fi
else
  printf '%s\n' 'Compose runtime phases skipped.'
fi

if [[ "$skip_kubernetes" == false ]]; then
  if [[ "$dry_run" == true ]]; then
    print_command bash "$repo_root/scripts/normalize-local-images-for-kind.sh" --tag "$image_tag"
    print_command kind load docker-image --name "$kind_cluster" "simplematch-matching:${image_tag}"
    print_command kubectl create namespace "$namespace"
    print_command kubectl create -f "$evidence_dir/local-kubernetes-inputs.yaml"
    print_command kubectl apply -f "$evidence_dir/local-kubernetes-platform.yaml"
    print_command kubectl apply -f "$evidence_dir/local-kubernetes-migrations.yaml"
    print_command kubectl wait --for=condition=complete job/account-service-flyway --timeout=300s
    print_command kubectl apply -f "$evidence_dir/local-kubernetes-workloads.yaml"
    print_command register_kubernetes_risk_connector
    print_command bash "$repo_root/scripts/verify-matching-fleet-live.sh" --namespace "$namespace" --allow-shared-node --allow-local-image
  else
    if [[ "$matching_fleet_only" == true ]]; then
      run_logged kubernetes-image-normalization docker image inspect \
        "simplematch-matching:${image_tag}"
      local_images=("simplematch-matching:${image_tag}")
    else
      run_logged kubernetes-image-normalization bash \
        "$repo_root/scripts/normalize-local-images-for-kind.sh" --tag "$image_tag"
      mapfile -t local_images < <(bash "$repo_root/scripts/build-local-images.sh" --tag "$image_tag" --list | awk -F'|' '{print $4}')
    fi
    run_logged kubernetes-image-load kind load docker-image --name "$kind_cluster" "${local_images[@]}"
    matching_digest="$(docker image inspect --format '{{.Id}}' "simplematch-matching:${image_tag}")"
    [[ "$matching_digest" =~ ^sha256:[0-9a-f]{64}$ ]] || die \
      "Matching local image does not expose an OCI image ID: $matching_digest"
    rendered_manifest="$(render_local_kubernetes_manifest)"
    platform_manifest="$evidence_dir/local-kubernetes-platform.yaml"
    migration_manifest="$evidence_dir/local-kubernetes-migrations.yaml"
    workload_manifest="$evidence_dir/local-kubernetes-workloads.yaml"
    input_manifest="$evidence_dir/local-kubernetes-inputs.yaml"
    run_logged kubernetes-manifest-split split_kubernetes_manifest \
      "$rendered_manifest" "$platform_manifest" "$migration_manifest" "$workload_manifest" "$input_manifest"
    if [[ "$resume" == true ]]; then
      printf 'Reusing certification namespace %s.\n' "$namespace"
    else
      create_certification_namespace
    fi
    run_logged kubernetes-inputs apply_local_kubernetes_inputs "$matching_digest" "$input_manifest"
    run_logged kubernetes-platform-apply kubectl apply -f "$platform_manifest"
    if [[ "$matching_fleet_only" == true ]]; then
      matching_workload_manifest="$evidence_dir/local-kubernetes-matching-workload.yaml"
      run_logged kubernetes-matching-manifest select_matching_workload \
        "$workload_manifest" "$matching_workload_manifest"
      run_logged kubernetes-topic-provisioning apply_kubernetes_topic_provisioning \
        "$migration_manifest"
      run_logged kubernetes-open-barriers publish_local_matching_open_barriers "$matching_digest"
      run_logged kubernetes-matching-apply kubectl apply -f "$matching_workload_manifest"
    else
    run_logged kubernetes-migrations apply_kubernetes_migrations "$migration_manifest"
    run_logged kubernetes-open-barriers publish_local_matching_open_barriers "$matching_digest"
    run_logged kubernetes-workload-apply kubectl apply -f "$workload_manifest"
    run_logged kubernetes-risk-outbox-connector register_kubernetes_risk_connector
    fi
    if [[ "$matching_fleet_only" == true ]]; then
      run_logged kubernetes-matching-workloads wait_for_local_matching_fleet
    else
      run_logged kubernetes-workloads wait_for_kubernetes_workloads
    fi
    run_logged kubernetes-fleet bash "$repo_root/scripts/verify-matching-fleet-live.sh" \
      --namespace "$namespace" --allow-shared-node --allow-local-image
  fi
else
  printf '%s\n' 'Kubernetes runtime phases skipped.'
fi

printf '%s\n' 'Local production-like certification workflow completed.'