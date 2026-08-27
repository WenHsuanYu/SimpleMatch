#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd -- "$script_dir/.." && pwd)"
manifest="$repo_root/deploy/k8s/kafka-kraft.yaml"

command -v ruby >/dev/null 2>&1 || {
  printf '%s\n' 'ruby is required.' >&2
  exit 1
}

broker_init="$(mktemp "${TMPDIR:-/tmp}/simplematch-kafka-kraft-init.XXXXXX")"
topic_provision="$(mktemp "${TMPDIR:-/tmp}/simplematch-kafka-kraft-topic.XXXXXX")"
fake_storage="$(mktemp "${TMPDIR:-/tmp}/simplematch-kafka-kraft-storage.XXXXXX")"
format_log="$(mktemp "${TMPDIR:-/tmp}/simplematch-kafka-kraft-format.XXXXXX")"
fresh_data="$(mktemp -d "${TMPDIR:-/tmp}/simplematch-kafka-kraft-fresh.XXXXXX")"
fresh_config="$(mktemp -d "${TMPDIR:-/tmp}/simplematch-kafka-kraft-config.XXXXXX")"
mismatch_data="$(mktemp -d "${TMPDIR:-/tmp}/simplematch-kafka-kraft-mismatch.XXXXXX")"
mismatch_config="$(mktemp -d "${TMPDIR:-/tmp}/simplematch-kafka-kraft-mismatch-config.XXXXXX")"
node_mismatch_data="$(mktemp -d "${TMPDIR:-/tmp}/simplematch-kafka-kraft-node-mismatch.XXXXXX")"
node_mismatch_config="$(mktemp -d "${TMPDIR:-/tmp}/simplematch-kafka-kraft-node-mismatch-config.XXXXXX")"
dirty_data="$(mktemp -d "${TMPDIR:-/tmp}/simplematch-kafka-kraft-dirty.XXXXXX")"
dirty_config="$(mktemp -d "${TMPDIR:-/tmp}/simplematch-kafka-kraft-dirty-config.XXXXXX")"
trap 'rm -f "$broker_init" "$topic_provision" "$fake_storage" "$format_log"; rm -rf "$fresh_data" "$fresh_config" "$mismatch_data" "$mismatch_config" "$node_mismatch_data" "$node_mismatch_config" "$dirty_data" "$dirty_config"' EXIT

ruby -rjson -ryaml - "$manifest" "$broker_init" "$topic_provision" <<'RUBY'
manifest_path, broker_init_path, topic_provision_path = ARGV

def load_documents(path)
  visitor = Psych::Visitors::ToRuby.create
  Psych.parse_stream(File.read(path, encoding: "UTF-8")).children.map { |document| visitor.accept(document) }.compact
end

def require_value(condition, message)
  return if condition

  warn message
  exit 1
end

documents = load_documents(manifest_path)
resources = documents.to_h { |document| [[document.fetch("kind"), document.dig("metadata", "name")], document] }
require_value(documents.length == 6, "Kafka KRaft manifest must contain exactly six resources")

headless = resources.fetch(["Service", "kafka-headless"])
bootstrap = resources.fetch(["Service", "kafka"])
config = resources.fetch(["ConfigMap", "kafka-kraft-config"])
statefulset = resources.fetch(["StatefulSet", "kafka"])
pdb = resources.fetch(["PodDisruptionBudget", "kafka"])
job = resources.fetch(["Job", "kafka-topic-provisioning"])

broker_selector = {
  "app.kubernetes.io/name" => "kafka",
  "app.kubernetes.io/component" => "broker"
}
require_value(headless.dig("spec", "clusterIP") == "None", "Kafka peer Service must be headless")
require_value(headless.dig("spec", "publishNotReadyAddresses") == true, "Kafka peer Service must publish quorum addresses before readiness")
require_value(headless.dig("spec", "selector") == broker_selector, "Kafka peer Service selector is not broker-scoped")
require_value(
  headless.fetch("spec").fetch("ports").map { |port| [port.fetch("name"), port.fetch("port")] }.sort ==
    [["controller", 9093], ["internal", 9092]],
  "Kafka peer Service must expose internal and controller ports"
)
require_value(bootstrap.dig("spec", "selector") == broker_selector, "Kafka bootstrap Service selector is not broker-scoped")
require_value(
  bootstrap.dig("spec", "ports", 0, "port") == 9092 &&
    bootstrap.dig("spec", "ports", 0, "targetPort") == "internal",
  "Kafka bootstrap Service must target kafka:9092"
)

spec = statefulset.fetch("spec")
template = spec.fetch("template")
pod_spec = template.fetch("spec")
container = pod_spec.fetch("containers").find { |candidate| candidate.fetch("name") == "kafka" }
init_container = pod_spec.fetch("initContainers").find { |candidate| candidate.fetch("name") == "kafka-kraft-init" }
require_value(spec.fetch("replicas") == 3, "Kafka StatefulSet must have three broker/controller identities")
require_value(spec.fetch("serviceName") == "kafka-headless", "Kafka StatefulSet must use kafka-headless")
require_value(spec.fetch("podManagementPolicy") == "Parallel", "Kafka StatefulSet must bootstrap quorum in parallel")
require_value(spec.dig("updateStrategy", "type") == "OnDelete", "Kafka updates must not silently reformat or roll quorum members")
require_value(
  spec.dig("persistentVolumeClaimRetentionPolicy") == {"whenDeleted" => "Retain", "whenScaled" => "Retain"},
  "Kafka PVC retention must preserve ordinal storage"
)
require_value(
  pod_spec.dig("nodeSelector", "simplematch.io/node-pool") == "local-resilience",
  "Kafka must run on the canonical local-resilience worker pool"
)
anti_affinity = pod_spec.dig("affinity", "podAntiAffinity", "requiredDuringSchedulingIgnoredDuringExecution", 0)
require_value(
  anti_affinity&.dig("topologyKey") == "kubernetes.io/hostname" &&
    anti_affinity.dig("labelSelector", "matchLabels") == broker_selector,
  "Kafka brokers must use required hostname anti-affinity"
)
spread = pod_spec.fetch("topologySpreadConstraints").find { |constraint| constraint.fetch("topologyKey") == "simplematch.io/worker-slot" }
require_value(
  spread && spread.fetch("maxSkew") == 1 && spread.fetch("whenUnsatisfiable") == "DoNotSchedule" &&
    spread.dig("labelSelector", "matchLabels") == broker_selector,
  "Kafka brokers must occupy one broker per worker slot"
)
require_value(container.fetch("image") == "apache/kafka:4.3.1", "Kafka image must match the Compose 4.3.1 profile")
require_value(init_container.fetch("image") == container.fetch("image"), "Kafka init and main images must match")
require_value(
  container.fetch("command") == ["/opt/kafka/bin/kafka-server-start.sh"] &&
    container.fetch("args") == ["/etc/kafka/generated/server.properties"],
  "Kafka must start from the generated identity-bound properties"
)
require_value(
  init_container.fetch("command") == ["/bin/sh", "/opt/kafka/scripts/broker-init.sh"],
  "Kafka init must use the statically testable bootstrap contract"
)
require_value(
  container.dig("resources", "requests") == {"cpu" => "500m", "memory" => "1Gi"} &&
    container.dig("resources", "limits") == {"cpu" => "1", "memory" => "2Gi"},
  "Kafka broker resources must be bounded"
)
require_value(
  container.dig("livenessProbe", "tcpSocket", "port") == "internal",
  "Kafka liveness must only check the broker listener"
)
require_value(
  job.dig("spec", "activeDeadlineSeconds").is_a?(Integer) &&
    job.dig("spec", "activeDeadlineSeconds").between?(1, 300),
  "Kafka topic provisioning must have a bounded active deadline"
)
job_container = job.dig("spec", "template", "spec", "containers", 0)
require_value(
  job_container.dig("resources", "requests") && job_container.dig("resources", "limits"),
  "Kafka topic provisioning resources must be bounded"
)
require_value(
  job.dig("metadata", "labels", "app.kubernetes.io/component") == "topic-provisioning",
  "Kafka topic provisioning must use the dedicated component label"
)
require_value(
  job.dig("spec", "backoffLimit") == 0 &&
    job.dig("spec", "template", "spec", "restartPolicy") == "Never" &&
    job_container.dig("env", 0, "name") == "KAFKA_BOOTSTRAP_SERVERS" &&
    job_container.dig("env", 0, "value") == "kafka:9092",
  "Kafka topic provisioning must retry against the local bootstrap Service"
)
require_value(!JSON.generate(job).include?("secretKeyRef"), "Local Kafka topic provisioning must not require credentials")

claim = spec.dig("volumeClaimTemplates", 0)
require_value(spec.fetch("volumeClaimTemplates").length == 1, "Kafka must declare one PVC template")
require_value(
  claim.dig("metadata", "name") == "kafka-data" &&
    claim.dig("spec", "accessModes") == ["ReadWriteOncePod"] &&
    claim.dig("spec", "storageClassName") == "simplematch-rwo-pod" &&
    claim.dig("spec", "resources", "requests", "storage") == "20Gi",
  "Kafka ordinal PVC must be dynamically provisioned and restart-safe"
)
require_value(
  pdb.dig("spec", "minAvailable") == 2 && pdb.dig("spec", "selector", "matchLabels") == broker_selector,
  "Kafka PDB must preserve two available brokers"
)

data = config.fetch("data")
cluster_id = data.fetch("cluster_id")
require_value(cluster_id == "5L6g3nShT-eMCtK--X86sw", "Kafka cluster ID fixture must remain fixed")
broker_init = data.fetch("broker-init.sh")
topic_provision = data.fetch("topic-provision.sh")
%w[meta.properties cluster.id node.id KAFKA_STORAGE_BIN format --cluster-id].each do |needle|
  require_value(broker_init.include?(needle), "Kafka bootstrap contract is missing #{needle}")
end
%w[
  matching.commands
  matching.events
  marketdata.events
  simplematch-connect-configs
  simplematch-connect-offsets
  simplematch-connect-status
  --partitions 15
  --partitions 25
  --replication-factor 3
  retention.ms=2592000000
  min.insync.replicas=2
].each do |needle|
  require_value(topic_provision.include?(needle), "Kafka topic contract is missing #{needle}")
end
require_value(
  topic_provision.include?("cleanup.policy=$cleanup_policy"),
  "Kafka topic provisioning must apply the requested cleanup policy"
)
%w[
  ensure_topic matching.commands 15 delete
  ensure_topic matching.events 15 delete
  ensure_topic marketdata.events 15 delete
  ensure_topic simplematch-connect-configs 1 compact
  ensure_topic simplematch-connect-offsets 25 compact
  ensure_topic simplematch-connect-status 3 compact
].each_slice(4) do |tokens|
  require_value(topic_provision.include?(tokens.join(" ")), "Kafka topic contract is missing #{tokens.join(" ")}")
end
producer = data.fetch("matching-producer.properties")
require_value(producer.lines.any? { |line| line.strip == "acks=all" }, "Matching producer must use acks=all")
require_value(producer.lines.any? { |line| line.strip == "enable.idempotence=true" }, "Matching producer must enable idempotence")
require_value(
  broker_init.include?("default.replication.factor=3") &&
    broker_init.include?("min.insync.replicas=2") &&
    broker_init.include?("offsets.topic.replication.factor=3") &&
    broker_init.include?("transaction.state.log.replication.factor=3") &&
    broker_init.include?("transaction.state.log.min.isr=2") &&
    broker_init.include?("auto.create.topics.enable=false") &&
    broker_init.include?("unclean.leader.election.enable=false") &&
    broker_init.include?("log.retention.ms=2592000000"),
  "Kafka broker durability settings must be explicit"
)
%w[kafka-0.kafka-headless kafka-1.kafka-headless kafka-2.kafka-headless].each do |dns_name|
  require_value(broker_init.include?(dns_name), "Kafka broker DNS is missing #{dns_name}")
end
%w[controller.quorum.voters advertised.listeners].each do |setting|
  require_value(broker_init.include?(setting), "Kafka identity setting is missing #{setting}")
end

File.write(broker_init_path, broker_init)
File.write(topic_provision_path, topic_provision)
RUBY

chmod +x "$broker_init" "$topic_provision"

printf '%s\n' \
  '#!/bin/sh' \
  'printf "%s\n" formatted >>"$KAFKA_STORAGE_LOG"' \
  'printf "%s\n" "version=1" "cluster.id=$KAFKA_CLUSTER_ID" "node.id=$NODE_ID" >"$KAFKA_DATA_DIR/meta.properties"' \
  >"$fake_storage"
chmod +x "$fake_storage"

cluster_id="5L6g3nShT-eMCtK--X86sw"
format_log_env=(
  KAFKA_STORAGE_BIN="$fake_storage"
  KAFKA_STORAGE_LOG="$format_log"
  KAFKA_CLUSTER_ID="$cluster_id"
  POD_NAME=kafka-1
  NODE_ID=1
)
run_bootstrap() {
  local data_dir="$1" config_dir="$2"
  env \
    KAFKA_DATA_DIR="$data_dir" \
    KAFKA_CONFIG_DIR="$config_dir" \
    "${format_log_env[@]}" \
    "$broker_init"
}

# Fresh bootstrap formats an empty PVC exactly once and binds its generated config to the ordinal.
: >"$format_log"
run_bootstrap "$fresh_data" "$fresh_config"
[[ "$(wc -l <"$format_log")" -eq 1 ]]
grep -Fq 'cluster.id=5L6g3nShT-eMCtK--X86sw' "$fresh_data/meta.properties"
grep -Fq 'node.id=1' "$fresh_data/meta.properties"
grep -Fq 'advertised.listeners=INTERNAL://kafka-1.kafka-headless:9092' "$fresh_config/server.properties"

# A persistent restart verifies metadata and must not invoke the formatter again.
: >"$format_log"
run_bootstrap "$fresh_data" "$fresh_config"
[[ ! -s "$format_log" ]]

# A cluster-ID mismatch and a node-ID mismatch both fail before the formatter can run.
printf '%s\n' 'version=1' "cluster.id=wrong-cluster" 'node.id=1' >"$mismatch_data/meta.properties"
: >"$format_log"
if run_bootstrap "$mismatch_data" "$mismatch_config"; then
  printf '%s\n' 'cluster-ID mismatch unexpectedly succeeded' >&2
  exit 1
fi
[[ ! -s "$format_log" ]]

printf '%s\n' "cluster.id=$cluster_id" 'node.id=0' >"$node_mismatch_data/meta.properties"
: >"$format_log"
if run_bootstrap "$node_mismatch_data" "$node_mismatch_config"; then
  printf '%s\n' 'node-ID mismatch unexpectedly succeeded' >&2
  exit 1
fi
[[ ! -s "$format_log" ]]

# Non-metadata contents are not treated as a fresh directory and cannot be overwritten.
printf '%s\n' 'committed-data' >"$dirty_data/segment.log"
if run_bootstrap "$dirty_data" "$dirty_config"; then
  printf '%s\n' 'non-empty unformatted data unexpectedly succeeded' >&2
  exit 1
fi
[[ ! -s "$format_log" ]]

bash -n "$script_dir/test-kafka-kraft-manifests.sh"
printf '%s\n' 'Kafka KRaft manifest and lifecycle contract checks passed.'
