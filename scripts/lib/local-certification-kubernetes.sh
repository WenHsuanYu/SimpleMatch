#!/usr/bin/env bash

# Local production-like Kubernetes manifest and input helpers.
# Sourced by run-local-production-like-certification.sh; shared run state is owned
# by the top-level orchestrator. This file defines behavior only and has no entry point.

render_local_kubernetes_manifest() {
  local rendered_manifest="$evidence_dir/local-kubernetes.yaml"
  mkdir -p "$evidence_dir"
  bash "$repo_root/scripts/render-local-kubernetes-manifest.sh" \
    --image-lock "$image_lock" \
    --namespace "$namespace" \
    --output "$rendered_manifest" >/dev/null
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
    --dry-run=client -o json \
    | jq '.immutable = true' \
    | kubectl apply -f - >/dev/null

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

assert_certification_namespace_exclusive() {
  local existing_namespace
  local conflicting_namespaces=()

  while IFS= read -r existing_namespace; do
    [[ -n "$existing_namespace" ]] || continue
    [[ "$existing_namespace" == "$namespace" ]] && continue
    conflicting_namespaces+=("$existing_namespace")
  done < <(kubectl --context "$kind_context" get namespaces \
    -l 'simplematch.io/managed-by=local-production-like-certification' \
    -o jsonpath='{range .items[*]}{.metadata.name}{"\n"}{end}')

  if (( ${#conflicting_namespaces[@]} > 0 )); then
    printf 'Another retained local production-like certification namespace exists: %s\n' \
      "${conflicting_namespaces[*]}" >&2
    printf '%s\n' 'Delete or explicitly finish that owned certification run before starting another.' >&2
    return 1
  fi
}

create_certification_namespace() {
  if kubectl --context "$kind_context" get namespace "$namespace" >/dev/null 2>&1; then
    die "Certification namespace already exists: $namespace"
  fi

  simplematch_kind_create_disposable_namespace \
    "$kind_context" "$namespace" local-production-like-certification "$run_id" || die \
    "Failed to create owned disposable certification namespace: $namespace"
  kubernetes_namespace_created=true
}

_certification_namespace_cleanup_owned() {
  local namespace_run_id

  [[ "${kubernetes_namespace_created:-false}" == true ]] && return 0
  [[ -n "${namespace:-}" && -n "${run_id:-}" ]] || return 1
  kubectl --context "$kind_context" get namespace "$namespace" >/dev/null 2>&1 || return 1
  simplematch_kind_namespace_is_disposable \
    "$kind_context" "$namespace" local-production-like-certification || return 1
  namespace_run_id="$(kubectl --context "$kind_context" get namespace "$namespace" \
    -o jsonpath='{.metadata.labels.simplematch\\.io/run-id}')" || return 1
  [[ "$namespace_run_id" == "$run_id" ]]
}

require_kubernetes_job_complete() {
  local job_name="$1"
  local conditions
  conditions="$(kubernetes_job_conditions "$job_name" 2>/dev/null || true)"
  grep -Eq '^Complete=True:' <<<"$conditions" || {
    printf 'Required Kubernetes Job %s is not complete: %s\n' \
      "$job_name" "${conditions//$'\n'/, }" >&2
    return 1
  }
}

collect_kafka_provisioning_evidence() {
  local output_dir="$1"
  local pod

  mkdir -p "$output_dir"
  kubectl --context "$kind_context" -n "$namespace" get statefulset kafka -o yaml \
    >"$output_dir/statefulset-kafka.yaml" 2>"$output_dir/statefulset-kafka.stderr" || true
  kubectl --context "$kind_context" -n "$namespace" get service kafka kafka-headless -o yaml \
    >"$output_dir/services.yaml" 2>"$output_dir/services.stderr" || true
  kubectl --context "$kind_context" -n "$namespace" get endpointslices \
    -l 'kubernetes.io/service-name=kafka' -o yaml \
    >"$output_dir/kafka-endpointslices.yaml" 2>"$output_dir/kafka-endpointslices.stderr" || true
  for pod in kafka-0 kafka-1 kafka-2; do
    kubectl --context "$kind_context" -n "$namespace" logs "$pod" --tail=250 \
      >"$output_dir/${pod}.tail.log" 2>"$output_dir/${pod}.tail.stderr" || true
  done
}

publish_local_matching_open_barriers() {
  local matching_digest="$1"
  local matching_runtime_image="$2"
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
    cmake --preset full-native-dev || return 1
  fi
  cmake --build --preset full-native-dev \
    --target simplematch-matching-kafka-fixture-publisher --parallel || return 1
  kubectl -n "$namespace" wait \
    --for=jsonpath='{.status.readyReplicas}'=3 statefulset/kafka --timeout=300s || return 1
  require_kubernetes_job_complete kafka-topic-provisioning || return 1
  kubectl -n "$namespace" delete pod "$fixture_pod" --ignore-not-found --wait=true >/dev/null || return 1
  kubectl -n "$namespace" run "$fixture_pod" \
    --image="$matching_runtime_image" --image-pull-policy=IfNotPresent \
    --restart=Never --command -- sleep 300 >/dev/null || return 1
  kubectl -n "$namespace" wait --for=condition=Ready "pod/$fixture_pod" --timeout=120s || return 1
  base64 "$fixture_publisher" | kubectl -n "$namespace" exec -i "$fixture_pod" -- \
    sh -c 'base64 -d >/tmp/simplematch-matching-kafka-fixture-publisher && chmod 755 /tmp/simplematch-matching-kafka-fixture-publisher' || return 1
  kubectl -n "$namespace" exec "$fixture_pod" -- \
    /tmp/simplematch-matching-kafka-fixture-publisher kafka:9092 matching.commands \
    "$certification_trading_day" "${certification_trading_day}-regular" "$artifact_sha256" \
    "$routing_version" "$matching_digest" || return 1
  kubectl -n "$namespace" delete pod "$fixture_pod" --ignore-not-found --wait=true >/dev/null || return 1
}

apply_kubernetes_migrations() {
  local migration_manifest="$1"
  local workload
  apply_kubernetes_topic_provisioning "$migration_manifest" || return 1
  for workload in account-service risk-service persistence market-data-projection marketdata-publisher query-service quickfix-gateway; do
    kubectl apply -f "$migration_manifest" \
      --selector "app.kubernetes.io/name=${workload}-flyway" || return 1
    kubectl -n "$namespace" wait --for=condition=complete \
      "job/${workload}-flyway" --timeout=300s || return 1
  done
}

apply_kubernetes_topic_provisioning() {
  local migration_manifest="$1"
  local job_evidence_dir="$evidence_dir/kubernetes-jobs/kafka-topic-provisioning"

  kubectl -n "$namespace" wait \
    --for=jsonpath='{.status.readyReplicas}'=3 statefulset/kafka --timeout=300s || return 1
  kubectl apply -f "$migration_manifest" \
    --selector 'app.kubernetes.io/component=topic-provisioning' || return 1
  if ! supervise_kubernetes_job kafka-topic-provisioning \
      "$kafka_topic_provisioning_supervisor_seconds" "$job_evidence_dir"; then
    collect_kafka_provisioning_evidence "$job_evidence_dir/kafka"
    return 1
  fi
}
