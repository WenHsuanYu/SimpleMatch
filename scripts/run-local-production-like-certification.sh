#!/usr/bin/env bash

set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd -- "$script_dir/.." && pwd)"

compose_file="$repo_root/deploy/compose/kafka-connect.production-like.yml"
compose_project="${SIMPLEMATCH_CERTIFICATION_COMPOSE_PROJECT:-simplematch-local-production-like}"
image_tag="${SIMPLEMATCH_LOCAL_IMAGE_TAG:-local}"
evidence_dir="${SIMPLEMATCH_CERTIFICATION_EVIDENCE_DIR:-$repo_root/out/certification/local-production-like}"
certification_trading_day="${SIMPLEMATCH_CERTIFICATION_TRADING_DAY:-$(date -u +%F)}"
namespace=""
kind_cluster="${SIMPLEMATCH_KIND_CLUSTER_NAME:-simplematch-local}"
dry_run=false
skip_build=false
skip_compose=false
skip_kubernetes=false
matching_fleet_only=false
keep_resources=false
compose_started=false
kubernetes_namespace_created=false
failure_reason=""
failed_phase=""
completion_status="RUNNING"
completed_phases=()
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
  --dry-run                Print planned commands without changing external state.
  --help                   Show this help.

The local gate owns only the Compose project named by
SIMPLEMATCH_CERTIFICATION_COMPOSE_PROJECT and a generated Kubernetes namespace.
It never pushes images and never touches staging or production resources.
The Kubernetes gate uses the approved delivery manifest for
SIMPLEMATCH_CERTIFICATION_TRADING_DAY (default: current UTC day) under
tools/market-reference-builder/data, or the path supplied by
SIMPLEMATCH_MARKET_REFERENCE_DELIVERY_MANIFEST.
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
    "$@"
  ) >>"$log_path" 2>&1
  command_status=$?
  set -e
  if [[ "$command_status" -eq 0 ]]; then
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

  if [[ "$dry_run" == true ]]; then
    print_command "$@"
    return 0
  fi

  mkdir -p "$(dirname -- "$output_path")" "$evidence_dir"
  local command_status
  set +e
  (
    set -e
    "$@"
  ) >"$output_path" 2>&1
  command_status=$?
  set -e
  if [[ "$command_status" -eq 0 ]]; then
    completed_phases+=("$phase")
    printf 'PASS %-32s (%s)\n' "$phase" "$output_path"
    return 0
  fi

  failed_phase="$phase"
  failure_reason="Phase failed: $phase"
  cat "$output_path" >&2
  return "$command_status"
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

if [[ "$skip_kubernetes" == false ]]; then
  if [[ "$dry_run" == false ]]; then
    command -v kubectl >/dev/null 2>&1 || die 'kubectl is required for the local Kubernetes gate.'
    command -v kind >/dev/null 2>&1 || die 'kind is required for the local Kubernetes gate; install kind or use --skip-kubernetes.'
    kind get clusters | grep -Fxq "$kind_cluster" || die \
      "kind cluster '$kind_cluster' does not exist; create it before running this gate."
  fi
  export SIMPLEMATCH_PRODUCTION_LIKE_NETWORK="${SIMPLEMATCH_PRODUCTION_LIKE_NETWORK:-kind}"
  export SIMPLEMATCH_PRODUCTION_LIKE_NETWORK_EXTERNAL="${SIMPLEMATCH_PRODUCTION_LIKE_NETWORK_EXTERNAL:-true}"
else
  export SIMPLEMATCH_PRODUCTION_LIKE_NETWORK="${SIMPLEMATCH_PRODUCTION_LIKE_NETWORK:-simplematch-production-like}"
  export SIMPLEMATCH_PRODUCTION_LIKE_NETWORK_EXTERNAL="${SIMPLEMATCH_PRODUCTION_LIKE_NETWORK_EXTERNAL:-false}"
fi

run_logged static-kubernetes-overlays bash "$repo_root/scripts/test-kubernetes-overlays.sh"
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

run_flyway_migrations() {
  local service
  local dsn='postgresql://simplematch:simplematch@postgres:5432/simplematch'
  for service in account-service risk-service persistence market-data-projection marketdata-publisher query-service quickfix-gateway; do
    run_logged "flyway-${service}" docker run --rm \
      --network "${SIMPLEMATCH_PRODUCTION_LIKE_NETWORK}" \
      --env SIMPLEMATCH_POSTGRES_DSN="$dsn" \
      --env SIMPLEMATCH_FLYWAY_SERVICE_ID="$service" \
      "simplematch/flyway-runner:${image_tag}"
  done
}

create_kafka_topics() {
  local topic
  for topic in matching.commands matching.events matching.executions account.lifecycle marketdata.events; do
    run_logged "kafka-create-${topic//./-}" "${compose_command[@]}" exec -T kafka-1 \
      /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka-1:29092 --create --if-not-exists \
      --topic "$topic" --partitions 15 --replication-factor 3 \
      --config cleanup.policy=delete --config retention.ms=2592000000 --config min.insync.replicas=2
  done
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
    --profile production --fixture-dir "$fixture_dir" --certify-production
}

container_ip_on_network() {
  local service="$1"
  local container_id
  container_id="$("${compose_command[@]}" ps -q "$service")"
  [[ -n "$container_id" ]] || die "No Compose container found for $service"
  docker inspect --format "{{(index .NetworkSettings.Networks \"${SIMPLEMATCH_PRODUCTION_LIKE_NETWORK}\").IPAddress}}" \
    "$container_id"
}

append_bridge_service() {
  local name="$1"
  local port="$2"
  local address="$3"
  cat <<EOF
---
apiVersion: v1
kind: Service
metadata:
  name: ${name}
  namespace: ${namespace}
spec:
  ports:
    - name: tcp
      port: ${port}
      targetPort: ${port}
---
apiVersion: v1
kind: Endpoints
metadata:
  name: ${name}
  namespace: ${namespace}
subsets:
  - addresses:
      - ip: ${address}
    ports:
      - name: tcp
        port: ${port}
EOF
}

prepare_kubernetes_bridge() {
  local bridge_manifest="$evidence_dir/compose-kubernetes-bridge.yaml"
  local postgres_ip redis_ip kafka_1_ip kafka_2_ip kafka_3_ip
  postgres_ip="$(container_ip_on_network postgres)"
  redis_ip="$(container_ip_on_network redis)"
  kafka_1_ip="$(container_ip_on_network kafka-1)"
  kafka_2_ip="$(container_ip_on_network kafka-2)"
  kafka_3_ip="$(container_ip_on_network kafka-3)"
  [[ -n "$postgres_ip" && -n "$redis_ip" && -n "$kafka_1_ip" && -n "$kafka_2_ip" && -n "$kafka_3_ip" ]] || \
    die "Compose containers are not attached to ${SIMPLEMATCH_PRODUCTION_LIKE_NETWORK}."

  {
    append_bridge_service postgres 5432 "$postgres_ip"
    append_bridge_service redis 6379 "$redis_ip"
    append_bridge_service kafka 29092 "$kafka_1_ip"
    append_bridge_service kafka-1 29092 "$kafka_1_ip"
    append_bridge_service kafka-2 29092 "$kafka_2_ip"
    append_bridge_service kafka-3 29092 "$kafka_3_ip"
  } >"$bridge_manifest"
  run_logged kubernetes-compose-bridge kubectl apply -f "$bridge_manifest"
}

render_local_kubernetes_manifest() {
  local rendered_manifest="$evidence_dir/local-kubernetes.yaml"
  local network_cidr
  mkdir -p "$evidence_dir"
  if [[ "$dry_run" == true ]]; then
    print_command kubectl kustomize "$repo_root/deploy/k8s/overlays/local" --load-restrictor LoadRestrictionsNone
    return 0
  fi
  network_cidr="$(docker network inspect "$SIMPLEMATCH_PRODUCTION_LIKE_NETWORK" \
    --format '{{range .IPAM.Config}}{{println .Subnet}}{{end}}' | awk '/\./ {print; exit}')"
  [[ "$network_cidr" =~ ^[0-9]{1,3}(\.[0-9]{1,3}){3}/[0-9]{1,2}$ ]] || die \
    "Production-like Docker network has no usable IPv4 subnet: $network_cidr"
  kubectl kustomize "$repo_root/deploy/k8s/overlays/local" --load-restrictor LoadRestrictionsNone >"$rendered_manifest"
  sed -i \
    -e "s/namespace: simplematch-local$/namespace: ${namespace}/g" \
    -e 's/kafka:9092/kafka:29092/g' \
    -e "s|cidr: 172.19.0.0/16|cidr: ${network_cidr}|g" \
    "$rendered_manifest"
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
           when "Deployment", "StatefulSet"
             workload_manifest
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
    --from-literal=postgres_dsn='postgresql://simplematch:simplematch@postgres:5432/simplematch' \
    --dry-run=client -o yaml | kubectl apply -f - >/dev/null

  for service in account-service risk-service persistence market-data-projection marketdata-publisher query-service quickfix-gateway; do
    kubectl -n "$namespace" create secret generic "${service}-secrets" \
      --from-literal=postgres_dsn='postgresql://simplematch:simplematch@postgres:5432/simplematch' \
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
  "$fixture_publisher" "localhost:${SIMPLEMATCH_KAFKA_1_PORT:-19092}" matching.commands \
    "$certification_trading_day" "${certification_trading_day}-regular" "$artifact_sha256" \
    "$routing_version" "$matching_digest"
}

apply_kubernetes_migrations() {
  local migration_manifest="$1"
  local workload
  for workload in account-service risk-service persistence market-data-projection marketdata-publisher query-service quickfix-gateway; do
    kubectl apply -f "$migration_manifest" \
      --selector "app.kubernetes.io/name=${workload}-flyway"
    kubectl -n "$namespace" wait --for=condition=complete \
      "job/${workload}-flyway" --timeout=300s
  done
}

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
    kubectl -n "$namespace" rollout status "deployment/${workload}" --timeout=300s
  done
  kubectl -n "$namespace" rollout status statefulset/matching --timeout=600s
  kubectl -n "$namespace" rollout status statefulset/quickfix-gateway --timeout=300s
}

wait_for_local_matching_fleet() {
  kubectl -n "$namespace" rollout status statefulset/matching --timeout=600s
}

if [[ "$skip_compose" == false ]]; then
  compose_started=true
  run_logged compose-up "${compose_command[@]}" up --detach --remove-orphans
  run_logged compose-wait wait_for_compose
  run_logged compose-status "${compose_command[@]}" ps
  create_kafka_topics
  collect_kafka_fixture
  if [[ "$matching_fleet_only" == false ]]; then
    run_flyway_migrations
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
    print_command kubectl apply -f "$evidence_dir/compose-kubernetes-bridge.yaml"
    print_command kubectl create -f "$evidence_dir/local-kubernetes-inputs.yaml"
    print_command kubectl apply -f "$evidence_dir/local-kubernetes-platform.yaml"
    print_command kubectl apply -f "$evidence_dir/local-kubernetes-migrations.yaml"
    print_command kubectl wait --for=condition=complete job/account-service-flyway --timeout=300s
    print_command kubectl apply -f "$evidence_dir/local-kubernetes-workloads.yaml"
    print_command bash "$repo_root/scripts/verify-matching-fleet-live.sh" --namespace "$namespace" --allow-shared-node --allow-local-image
  else
    run_logged kubernetes-image-normalization bash \
      "$repo_root/scripts/normalize-local-images-for-kind.sh" --tag "$image_tag"
    mapfile -t local_images < <(bash "$repo_root/scripts/build-local-images.sh" --tag "$image_tag" --list | awk -F'|' '{print $4}')
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
    create_certification_namespace
    run_logged kubernetes-inputs apply_local_kubernetes_inputs "$matching_digest" "$input_manifest"
    prepare_kubernetes_bridge
    run_logged kubernetes-platform-apply kubectl apply -f "$platform_manifest"
    if [[ "$matching_fleet_only" == true ]]; then
      matching_workload_manifest="$evidence_dir/local-kubernetes-matching-workload.yaml"
      run_logged kubernetes-matching-manifest select_matching_workload \
        "$workload_manifest" "$matching_workload_manifest"
      run_logged kubernetes-matching-apply kubectl apply -f "$matching_workload_manifest"
    else
      run_logged kubernetes-migrations apply_kubernetes_migrations "$migration_manifest"
      run_logged kubernetes-workload-apply kubectl apply -f "$workload_manifest"
    fi
    run_logged kubernetes-open-barriers publish_local_matching_open_barriers "$matching_digest"
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
