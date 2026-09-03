#!/usr/bin/env bash
set -Eeuo pipefail
IFS=$'\n\t'

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd -- "$script_dir/.." && pwd)"
runner="$script_dir/run-local-production-like-certification.sh"
job_lib="$script_dir/lib/local-certification-job.sh"
kubernetes_lib="$script_dir/lib/local-certification-kubernetes.sh"
bootstrap_lib="$script_dir/lib/local-certification-bootstrap.sh"
run_lib="$script_dir/lib/local-certification-run.sh"
phase_graph_lib="$script_dir/lib/local-certification-phase-graph.sh"
kafka_manifest="$repo_root/deploy/k8s/kafka-kraft.yaml"
# shellcheck source=scripts/lib/local-certification-phase-graph.sh
source "$phase_graph_lib"

fail() {
  printf 'Local certification Job supervision contract: %s\n' "$*" >&2
  exit 1
}

for file in \
    "$runner" "$job_lib" "$kubernetes_lib" "$bootstrap_lib" "$run_lib" \
    "$phase_graph_lib"; do
  bash -n "$file"
done

grep -Fq 'SIMPLEMATCH_KUBERNETES_JOB_EVIDENCE_INTERVAL_SECONDS:-10' "$runner" ||
  fail 'runner must retain the Job evidence interval default'
grep -Fq 'SIMPLEMATCH_KAFKA_TOPIC_PROVISIONING_SUPERVISOR_SECONDS:-630' "$runner" ||
  fail 'runner must retain the Kafka provisioning supervisor default'
grep -Fq 'local-certification-job.sh' "$runner" "$bootstrap_lib" ||
  fail 'runner must load the shared Job supervision module'
grep -Fq 'supervise_kubernetes_job()' "$job_lib" ||
  fail 'Job supervisor function is missing'
grep -Fq '^Complete=True:' "$job_lib" ||
  fail 'Job supervisor no longer recognizes successful completion'
grep -Fq '^(Failed|FailureTarget)=True:' "$job_lib" ||
  fail 'Job supervisor no longer recognizes terminal failure'
grep -Fq 'collect_kubernetes_job_evidence' "$job_lib" ||
  fail 'Job supervisor no longer collects diagnostic evidence'
grep -Fq 'collect_kafka_provisioning_evidence' "$kubernetes_lib" ||
  fail 'Kafka provisioning evidence collector is missing'
grep -Fq 'kafka-endpointslices.yaml' "$kubernetes_lib" ||
  fail 'Kafka provisioning evidence no longer includes EndpointSlices'
grep -Fq 'assert_certification_namespace_exclusive' "$kubernetes_lib" "$bootstrap_lib" ||
  fail 'certification namespace exclusivity check is missing'
grep -Fq 'simplematch.io/managed-by=local-production-like-certification' "$kubernetes_lib" ||
  fail 'certification namespace ownership label is missing'
grep -Fq 'down --volumes --remove-orphans' "$run_lib" ||
  fail 'Compose fixture shutdown no longer removes run-owned volumes'

skip_build=false
skip_compose=false
skip_kubernetes=false
matching_fleet_only=false
image_transport=registry
namespace_dependencies="$(certification_phase_dependencies kubernetes-namespace)" ||
  fail 'Kubernetes namespace dependencies could not be resolved'
grep -Fxq compose-down-before-kubernetes <<<"$namespace_dependencies" ||
  fail 'Kubernetes execution must depend on Compose fixture shutdown'

if grep -Fq 'job/kafka-topic-provisioning --timeout=300s' "$kubernetes_lib"; then
  fail 'Kafka provisioning still has the legacy 300s outer Job wait'
fi

mapfile -t hierarchy < <(ruby -ryaml - "$kafka_manifest" "$runner" <<'RUBY'
docs = YAML.load_stream(File.read(ARGV.fetch(0))).compact
job = docs.find { |doc| doc["kind"] == "Job" && doc.dig("metadata", "name") == "kafka-topic-provisioning" }
raise "Kafka topic provisioning Job is missing" unless job
env = job.dig("spec", "template", "spec", "containers", 0, "env").to_h { |entry| [entry.fetch("name"), entry.fetch("value")] }
runner = File.read(ARGV.fetch(1))
supervisor = runner.match(/SIMPLEMATCH_KAFKA_TOPIC_PROVISIONING_SUPERVISOR_SECONDS:-([0-9]+)/)&.captures&.first
puts env.fetch("KAFKA_ADMIN_OPERATION_TIMEOUT_SECONDS")
puts env.fetch("KAFKA_BOOTSTRAP_RETRY_SECONDS")
puts job.dig("spec", "activeDeadlineSeconds")
puts supervisor || raise("Kafka supervisor default is missing")
RUBY
)

admin_timeout="${hierarchy[0]}"
retry_budget="${hierarchy[1]}"
job_deadline="${hierarchy[2]}"
supervisor_deadline="${hierarchy[3]}"
(( admin_timeout < retry_budget && retry_budget < job_deadline && job_deadline < supervisor_deadline )) || {
  printf 'Invalid Kafka timeout hierarchy: %s < %s < %s < %s\n' \
    "$admin_timeout" "$retry_budget" "$job_deadline" "$supervisor_deadline" >&2
  exit 1
}
[[ "$admin_timeout" == 15 && "$retry_budget" == 180 && "$job_deadline" == 600 && "$supervisor_deadline" == 630 ]] || {
  fail 'Kafka certification timeout defaults changed without updating the contract'
}

ruby -ryaml - "$kafka_manifest" <<'RUBY'
docs = YAML.load_stream(File.read(ARGV.fetch(0))).compact
stateful = docs.find { |doc| doc["kind"] == "StatefulSet" && doc.dig("metadata", "name") == "kafka" }
raise "Kafka StatefulSet is missing" unless stateful
container = stateful.dig("spec", "template", "spec", "containers").find { |item| item["name"] == "kafka" }
raise "Kafka startup probe must retain protocol-level verification" unless container.dig("startupProbe", "exec", "command")&.include?("/opt/kafka/bin/kafka-broker-api-versions.sh")
raise "Kafka startup timeout must tolerate loaded local hosts" unless container.dig("startupProbe", "timeoutSeconds") == 10
raise "Kafka readiness must use the listener seam" unless container.dig("readinessProbe", "tcpSocket", "port") == "internal"
raise "Kafka liveness must use the listener seam" unless container.dig("livenessProbe", "tcpSocket", "port") == "internal"
job = docs.find { |doc| doc["kind"] == "Job" && doc.dig("metadata", "name") == "kafka-topic-provisioning" }
raise "Kafka provisioning must not multiply retry layers" unless job.dig("spec", "backoffLimit") == 0
raise "Kafka provisioning Pod must expose one attempt" unless job.dig("spec", "template", "spec", "restartPolicy") == "Never"
config = docs.find { |doc| doc["kind"] == "ConfigMap" && doc.dig("metadata", "name") == "kafka-kraft-config" }
script = config.dig("data", "topic-provision.sh")
raise "Kafka Admin operations are not individually bounded" unless script.include?("timeout --foreground")
raise "Legacy 60x5 bootstrap retry loop remains" if script.include?('attempts" -lt 60')
RUBY

propagation_fixture="$(mktemp -d "${TMPDIR:-/tmp}/simplematch-certification-propagation.XXXXXX")"
trap 'rm -rf "$propagation_fixture"' EXIT

if ! (
  set -Eeuo pipefail
  namespace=certification-test
  evidence_dir="$propagation_fixture/evidence"
  calls_file="$propagation_fixture/migrations.calls"
  touch "$calls_file"
  # shellcheck source=/dev/null
  source "$kubernetes_lib"
  apply_kubernetes_topic_provisioning() {
    printf '%s\n' topic-provisioning >>"$calls_file"
    return 1
  }
  kubectl() {
    printf '%s\n' "$*" >>"$calls_file"
    return 0
  }
  set +e
  apply_kubernetes_migrations "$propagation_fixture/migrations.yaml"
  status=$?
  set -e
  [[ "$status" -ne 0 ]]
  ! grep -Fq -- '--selector app.kubernetes.io/name=account-service-flyway' "$calls_file"
); then
  fail 'a failed topic-provisioning Job must abort Kubernetes migrations before Flyway Jobs are applied'
fi

if ! (
  set -Eeuo pipefail
  namespace=certification-test
  calls_file="$propagation_fixture/migrations-order.calls"
  touch "$calls_file"
  # shellcheck source=/dev/null
  source "$kubernetes_lib"
  apply_kubernetes_topic_provisioning() {
    printf '%s\n' topic-provisioning >>"$calls_file"
  }
  kubectl() {
    printf '%s\n' "$*" >>"$calls_file"
  }
  apply_kubernetes_migrations "$propagation_fixture/migrations.yaml"
  mapfile -t apply_lines < <(grep -n -- '--selector app.kubernetes.io/name=' "$calls_file" | cut -d: -f1)
  mapfile -t wait_lines < <(grep -n -- '--for=condition=complete' "$calls_file" | cut -d: -f1)
  [[ "${#apply_lines[@]}" -eq 7 && "${#wait_lines[@]}" -eq 7 ]]
  for line in "${apply_lines[@]}"; do
    (( line < wait_lines[0] ))
  done
); then
  fail 'Flyway migration Jobs must all be submitted before any completion wait'
fi

if ! (
  set -Eeuo pipefail
  namespace=certification-test
  repo_root="$propagation_fixture"
  certification_trading_day=2026-08-27
  matching_fixture="$propagation_fixture/out/build/full-native-dev/simplematch-matching-kafka-fixture-publisher"
  mkdir -p "$(dirname -- "$matching_fixture")" "$propagation_fixture/market-reference/delivery"
  printf '#!/bin/sh\n' >"$matching_fixture"
  chmod 755 "$matching_fixture"
  printf '%s\n' 'name: market-reference-20260827-approved' >"$propagation_fixture/market-reference/delivery/manifest.yaml"
  printf '%s\n' '{"metadata":{"routingAlgorithmVersion":"test"}}' >"$propagation_fixture/market-reference/market_reference.json"
  printf '%064d\n' 0 >"$propagation_fixture/market-reference/market_reference.sha256"
  SIMPLEMATCH_MARKET_REFERENCE_DELIVERY_MANIFEST="$propagation_fixture/market-reference/delivery/manifest.yaml"
  evidence_dir="$propagation_fixture/evidence"
  calls_file="$propagation_fixture/barriers.calls"
  touch "$calls_file"
  # shellcheck source=/dev/null
  source "$kubernetes_lib"
  require_kubernetes_job_complete() {
    printf '%s\n' topic-check >>"$calls_file"
    return 1
  }
  kubectl() {
    printf '%s\n' "$*" >>"$calls_file"
    return 0
  }
  set +e
  publish_local_matching_open_barriers sha256:test matching:test
  status=$?
  set -e
  [[ "$status" -ne 0 ]]
  ! grep -Fq -- ' run matching-fixture-publisher' "$calls_file"
); then
  fail 'a failed topic-provisioning Job check must abort Matching open-barrier publication'
fi

if (
  set -Eeuo pipefail
  namespace=certification-test
  run_id=run-123
  kind_context=kind-simplematch-live
  kubernetes_namespace_created=false
  # shellcheck source=/dev/null
  source "$kubernetes_lib"
  kubectl() {
    if [[ "$*" == *run-id* ]]; then
      [[ "$*" == *'simplematch\.io/run-id'* ]] || return 1
      printf '%s' "$run_id"
    fi
    return 0
  }
  simplematch_kind_namespace_is_disposable() { return 0; }
  _certification_namespace_cleanup_owned
); then
  :
else
  fail 'cleanup must recognize an owned namespace even when creation state crossed a phase subshell'
fi

printf '%s\n' 'Local certification Job supervision contracts are valid.'
