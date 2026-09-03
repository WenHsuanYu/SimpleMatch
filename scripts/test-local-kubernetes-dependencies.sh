#!/usr/bin/env bash

set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd -- "$script_dir/.." && pwd)"
rendered="$(mktemp "${TMPDIR:-/tmp}/simplematch-local-dependencies.XXXXXX.yaml")"
trap 'rm -f "$rendered"' EXIT

command -v kubectl >/dev/null 2>&1 || { printf '%s\n' 'kubectl is required.' >&2; exit 1; }
kubectl kustomize "$repo_root/deploy/k8s/overlays/local" --load-restrictor LoadRestrictionsNone >"$rendered"

ruby - "$rendered" <<'RUBY'
require "json"
require "psych"

rendered_path = ARGV.fetch(0)
visitor = Psych::Visitors::ToRuby.create
documents = Psych.parse_stream(File.read(rendered_path, encoding: "UTF-8")).children
  .map { |document| visitor.accept(document) }.compact
resources = documents.to_h do |document|
  [[document.fetch("kind"), document.fetch("metadata").fetch("name")], document]
end

def require_value(condition, message)
  return if condition

  warn message
  exit 1
end

def postgres_credentials_from_secret?(environment, owner_prefix)
  %w[USER PASSWORD].all? do |credential|
    environment.dig(
      "#{owner_prefix}_POSTGRES_#{credential}",
      "valueFrom",
      "secretKeyRef",
      "name"
    ) == "simplematch-postgres-secrets"
  end
end

postgres = resources.fetch(["StatefulSet", "postgres"])
postgres_spec = postgres.fetch("spec").fetch("template").fetch("spec")
postgres_container = postgres_spec.fetch("containers").find { |container| container.fetch("name") == "postgres" }
postgres_claim = postgres.fetch("spec").fetch("volumeClaimTemplates").fetch(0)
postgres_pdb = resources.fetch(["PodDisruptionBudget", "postgres"])
postgres_service = resources.fetch(["Service", "postgres"])

require_value(postgres.fetch("spec").fetch("replicas") == 1, "PostgreSQL must be a singleton")
require_value(
  postgres_spec.dig("nodeSelector", "simplematch.io/node-pool") == "local-resilience" &&
    postgres_spec.dig("nodeSelector", "simplematch.io/worker-slot") == "0",
  "PostgreSQL must remain on local worker slot 0"
)
require_value(postgres_claim.dig("spec", "storageClassName") == "simplematch-rwo-pod", "PostgreSQL must use the local RWO StorageClass")
require_value(postgres_claim.dig("spec", "accessModes") == ["ReadWriteOnce"], "PostgreSQL must use one RWO PVC")
require_value(postgres_pdb.dig("spec", "minAvailable") == 1, "PostgreSQL PDB must protect the sole replica")
require_value(postgres_container&.dig("resources", "requests") && postgres_container.dig("resources", "limits"), "PostgreSQL must define resources")
require_value(postgres_container&.key?("readinessProbe"), "PostgreSQL must define a readiness probe")
require_value(postgres_container&.dig("command").nil?, "PostgreSQL must preserve the image entrypoint")
require_value(postgres_container&.dig("args") == ["-c", "wal_level=logical"], "PostgreSQL must pass wal_level through container args")
require_value(postgres_service.dig("spec", "ports", 0, "port") == 5432, "PostgreSQL Service must expose 5432")

redis = resources.fetch(["Deployment", "redis"])
redis_spec = redis.fetch("spec").fetch("template").fetch("spec")
redis_container = redis_spec.fetch("containers").find { |container| container.fetch("name") == "redis" }
redis_toleration = redis_spec.fetch("tolerations", []).find do |toleration|
  toleration.dig("effect") == "NoExecute" && toleration.dig("tolerationSeconds") == 30
end
redis_service = resources.fetch(["Service", "redis"])

require_value(redis.fetch("spec").fetch("replicas") == 1, "Redis must be a singleton")
require_value(redis_spec.dig("nodeSelector", "simplematch.io/node-pool") == "local-resilience", "Redis must use local-resilience workers")
require_value(!redis_spec.dig("nodeSelector", "simplematch.io/worker-slot"), "Redis must remain portable across worker slots")
require_value(redis_toleration, "Redis must have the 30-second portable-workload toleration")
require_value(!resources.key?(["PodDisruptionBudget", "redis"]), "Redis must not have a PDB")
require_value(redis_container&.dig("resources", "requests") && redis_container.dig("resources", "limits"), "Redis must define resources")
require_value(redis_container&.key?("readinessProbe"), "Redis must define a readiness probe")
require_value(redis_service.dig("spec", "ports", 0, "port") == 6379, "Redis Service must expose 6379")

kafka = resources.fetch(["StatefulSet", "kafka"])
kafka_spec = kafka.fetch("spec").fetch("template").fetch("spec")
kafka_container = kafka_spec.fetch("containers").find { |container| container.fetch("name") == "kafka" }
kafka_claims = kafka.fetch("spec").fetch("volumeClaimTemplates")
kafka_pdb = resources.fetch(["PodDisruptionBudget", "kafka"])
kafka_service = resources.fetch(["Service", "kafka"])
kafka_headless = resources.fetch(["Service", "kafka-headless"])
topic_job = resources.fetch(["Job", "kafka-topic-provisioning"])
kafka_volume_mounts = kafka_container.fetch("volumeMounts")
kafka_volumes = kafka_spec.fetch("volumes")

require_value(kafka.fetch("spec").fetch("replicas") == 3, "Kafka must have three broker/controller replicas")
require_value(kafka_spec.dig("nodeSelector", "simplematch.io/node-pool") == "local-resilience", "Kafka must use local-resilience workers")
require_value(kafka_claims.length == 1 && kafka_claims.fetch(0).dig("spec", "storageClassName") == "simplematch-rwo-pod", "Kafka must use one local PVC per ordinal")
require_value(kafka_pdb.dig("spec", "minAvailable") == 2, "Kafka PDB must retain two brokers")
require_value(kafka_container&.dig("resources", "requests") && kafka_container.dig("resources", "limits"), "Kafka must define resources")
require_value(kafka_container&.key?("readinessProbe") && kafka_container.key?("startupProbe"), "Kafka must define startup and readiness probes")
require_value(
  kafka_volume_mounts.any? { |mount| mount["name"] == "kafka-logs" && mount["mountPath"] == "/opt/kafka/logs" },
  "Kafka must provide a writable log path"
)
require_value(
  kafka_volumes.any? { |volume| volume["name"] == "kafka-logs" && volume["emptyDir"] == {} },
  "Kafka log path must use an ephemeral writable volume"
)
require_value(kafka_service.dig("spec", "ports", 0, "port") == 9092, "Kafka bootstrap Service must expose 9092")
require_value(kafka_headless.dig("spec", "clusterIP") == "None", "Kafka broker DNS must use a headless Service")
require_value(topic_job.dig("spec", "activeDeadlineSeconds") == 600, "Kafka topic provisioning must use the 600-second Job deadline")
topic_container = topic_job.dig("spec", "template", "spec", "containers", 0)
require_value(topic_container&.dig("resources", "requests") && topic_container.dig("resources", "limits"), "Kafka topic provisioning must define resources")

connect = resources.fetch(["Deployment", "kafka-connect"])
connect_pdb = resources.fetch(["PodDisruptionBudget", "kafka-connect"])
connect_spec = connect.fetch("spec").fetch("template").fetch("spec")
connect_container = connect_spec.fetch("containers").find { |container| container.fetch("name") == "kafka-connect" }
connect_service = resources.fetch(["Service", "kafka-connect"])
connect_network_policy = resources.fetch(["NetworkPolicy", "simplematch-kafka-connect"])
connect_environment = connect_container.fetch("env").to_h { |entry| [entry.fetch("name"), entry] }
connect_config = resources.fetch(["ConfigMap", "simplematch-kafka-connect-config"])
connector_configmap = resources.fetch(["ConfigMap", "risk-service-outbox-connector"])
connector_document = JSON.parse(connector_configmap.dig("data", "connector.json"))
connector_values = connector_document.fetch("config")
account_connector_configmap = resources.fetch(["ConfigMap", "account-service-outbox-connector"])
account_connector_document = JSON.parse(account_connector_configmap.dig("data", "connector.json"))
account_connector_values = account_connector_document.fetch("config")
marketdata_connector_configmap = resources.fetch(["ConfigMap", "marketdata-publisher-outbox-connector"])
marketdata_connector_document = JSON.parse(marketdata_connector_configmap.dig("data", "connector.json"))
marketdata_connector_values = marketdata_connector_document.fetch("config")
connect_volume_mounts = connect_container.fetch("volumeMounts")
connect_volumes = connect_spec.fetch("volumes")

require_value(connect.fetch("spec").fetch("replicas") == 2, "Local Kafka Connect must retain two workers")
require_value(connect_pdb.dig("spec", "minAvailable") == 1, "Kafka Connect PDB must retain one worker")
require_value(
  connect_spec.dig("nodeSelector", "simplematch.io/node-pool") == "local-resilience",
  "Local Kafka Connect must use local-resilience workers"
)
require_value(
  connect_config.dig("data", "bootstrap_servers") == "kafka:9092" &&
    connect_environment.dig("CONNECT_SECURITY_PROTOCOL", "value") == "PLAINTEXT",
  "Local Kafka Connect must use the in-cluster plaintext Kafka endpoint"
)
require_value(
  connect_environment.dig("CONNECT_CONFIG_STORAGE_REPLICATION_FACTOR", "value") == "3" &&
    connect_environment.dig("CONNECT_OFFSET_STORAGE_REPLICATION_FACTOR", "value") == "3" &&
    connect_environment.dig("CONNECT_STATUS_STORAGE_REPLICATION_FACTOR", "value") == "3" &&
    connect_environment.keys.none? { |name| name.end_with?("_MIN_ISR") },
  "Local Kafka Connect internal topics must declare replication factor three; topic ISR belongs to provisioning"
)
require_value(
  connect_environment.dig("CONNECT_CONFIG_PROVIDERS", "value") == "envvarprovider" &&
    connect_environment.dig("CONNECT_CONFIG_PROVIDERS_ENVVARPROVIDER_CLASS", "value") ==
      "org.apache.kafka.common.config.provider.EnvVarConfigProvider" &&
    !connector_values.key?("config.providers") &&
    !connector_values.key?("config.providers.envvarprovider.class") &&
    connector_values["database.hostname"] == "${envvarprovider:SIMPLEMATCH_POSTGRES_HOSTNAME}",
  "Local Kafka Connect must configure EnvVarConfigProvider on the worker and use its placeholders"
)
[["RISK_SERVICE", "Risk"], ["ACCOUNT_SERVICE", "Account"],
 ["MARKETDATA_PUBLISHER", "marketdata-publisher"]].each do |owner_prefix, owner_name|
  require_value(
    postgres_credentials_from_secret?(connect_environment, owner_prefix),
    "Local Kafka Connect must receive #{owner_name} database credentials from the local Secret"
  )
end
require_value(
  connector_document["name"] == "risk-service-outbox" &&
    connector_values["table.include.list"] == "risk_service.outbox" &&
    account_connector_document["name"] == "account-service-outbox" &&
    account_connector_values["table.include.list"] == "account_service.outbox" &&
    marketdata_connector_document["name"] == "marketdata-publisher-outbox" &&
    marketdata_connector_values["table.include.list"] == "marketdata_publisher.outbox",
  "Local Kafka Connect must retain owner-scoped Risk, Account, and marketdata connectors"
)
require_value(connect_container&.dig("resources", "requests") && connect_container.dig("resources", "limits"), "Local Kafka Connect must define resources")
require_value(connect_container&.key?("readinessProbe") && connect_container.key?("startupProbe"), "Local Kafka Connect must define bounded probes")
require_value(
  connect_spec.dig("securityContext", "runAsUser") == 1001 &&
    connect_spec.dig("securityContext", "runAsGroup") == 1001 &&
    connect_spec.dig("securityContext", "fsGroup") == 1001,
  "Local Kafka Connect must declare its numeric kafka identity"
)
%w[/kafka/config /kafka/data /kafka/logs].each do |mount_path|
  require_value(
    connect_volume_mounts.any? { |mount| mount["mountPath"] == mount_path },
    "Local Kafka Connect must provide a writable #{mount_path}"
  )
end
%w[kafka-config kafka-data kafka-logs].each do |volume_name|
  require_value(
    connect_volumes.any? { |volume| volume["name"] == volume_name && volume["emptyDir"] == {} },
    "Local Kafka Connect #{volume_name} volume must be ephemeral and writable"
  )
end
require_value(connect_service.dig("spec", "ports", 0, "port") == 8083, "Kafka Connect Service must expose 8083")
require_value(
  connect_network_policy.dig("spec", "podSelector", "matchLabels") == {
    "app.kubernetes.io/name" => "kafka-connect",
    "app.kubernetes.io/component" => "connector"
  },
  "Local Kafka Connect must have a connector-specific NetworkPolicy selector"
)
egress_rules = connect_network_policy.dig("spec", "egress") || []
connect_labels = {
  "app.kubernetes.io/name" => "kafka-connect",
  "app.kubernetes.io/component" => "connector"
}
postgres_labels = {
  "app.kubernetes.io/name" => "postgres",
  "app.kubernetes.io/component" => "database",
  "app.kubernetes.io/part-of" => "simplematch"
}
kafka_labels = {
  "app.kubernetes.io/name" => "kafka",
  "app.kubernetes.io/component" => "broker",
  "app.kubernetes.io/part-of" => "simplematch"
}
dns_labels = {"kubernetes.io/metadata.name" => "kube-system"}
normalize_network_policy_rule = lambda do |rule, destination_key|
  destinations = rule.fetch(destination_key, []).map do |destination|
    selector_kind = %w[podSelector namespaceSelector ipBlock].find { |kind| destination.key?(kind) }
    [selector_kind, destination.fetch(selector_kind)]
  end
  ports = rule.fetch("ports", []).map { |entry| [entry.fetch("protocol"), entry.fetch("port")] }.sort
  [destinations, ports]
end
expected_ingress_rules = [
  [["podSelector", {"matchLabels" => connect_labels}], [["TCP", 8083]]]
].map do |destination, ports|
  [[destination], ports]
end
actual_ingress_rules = connect_network_policy.dig("spec", "ingress").map {
  |rule| normalize_network_policy_rule.call(rule, "from")
}
require_value(
  actual_ingress_rules == expected_ingress_rules,
  "Local Kafka Connect NetworkPolicy ingress rules must match the exact TCP and destination contract"
)
expected_egress_rules = [
  [["podSelector", {"matchLabels" => connect_labels}], [["TCP", 8083]]],
  [["podSelector", {"matchLabels" => postgres_labels}], [["TCP", 5432]]],
  [["podSelector", {"matchLabels" => kafka_labels}], [["TCP", 9092]]],
  [["namespaceSelector", {"matchLabels" => dns_labels}], [["TCP", 53], ["UDP", 53]]]
].map do |destination, ports|
  [[destination], ports]
end
actual_egress_rules = egress_rules.map { |rule| normalize_network_policy_rule.call(rule, "to") }
require_value(
  actual_egress_rules == expected_egress_rules,
  "Local Kafka Connect NetworkPolicy egress rules must match the exact TCP, UDP, and destination contract"
)
require_value(
  connect_spec.fetch("volumes", []).none? { |volume| volume.dig("name") == "postgres-tls" || volume.dig("name") == "kafka-tls" },
  "Local Kafka Connect must not require external TLS volumes"
)

flyway_jobs = resources.values.select do |resource|
  resource["kind"] == "Job" && resource.dig("metadata", "name")&.end_with?("-flyway")
end
require_value(flyway_jobs.length == 7, "Local overlay must contain all seven Flyway Jobs")
flyway_jobs.each do |job|
  job_name = job.fetch("metadata").fetch("name")
  job_spec = job.fetch("spec")
  container = job_spec.dig("template", "spec", "containers", 0)
  wait_container = job_spec.dig("template", "spec", "initContainers", 0)
  require_value(job_spec["activeDeadlineSeconds"] == 300, "#{job_name} must have a bounded deadline")
  require_value(container&.dig("resources", "requests") && container.dig("resources", "limits"), "#{job_name} must define resources")
  require_value(wait_container&.fetch("name") == "wait-for-postgres", "#{job_name} must wait for PostgreSQL readiness")
  require_value(wait_container.dig("resources", "requests") && wait_container.dig("resources", "limits"), "#{job_name} readiness gate must define resources")
  require_value(
    wait_container.dig("securityContext", "runAsUser") == 999 &&
      wait_container.dig("securityContext", "runAsGroup") == 999,
    "#{job_name} PostgreSQL readiness gate must use the image's numeric postgres identity"
  )
end

require_value(!resources.key?(["NetworkPolicy", "simplematch-java-services-local-bridge"]), "local workloads must not depend on the old Compose bridge")
require_value(
  resources.fetch(["NetworkPolicy", "simplematch-java-services-kubernetes-api"]).dig("spec", "egress")&.any? { |rule|
    rule.dig("to", 0, "ipBlock", "cidr") == "10.96.0.0/12" && rule.dig("ports", 0, "port") == 443
  },
  "local Java workloads must be allowed to read the Kubernetes API"
)
require_value(
  resources.fetch(["NetworkPolicy", "simplematch-java-services-kubernetes-api"]).dig("spec", "egress")&.any? { |rule|
    rule.dig("to", 0, "ipBlock", "cidr") == "172.18.0.0/16" && rule.dig("ports", 0, "port") == 6443
  },
  "local Java workloads must be allowed to reach the kind control-plane API"
)
puts "Local Kubernetes dependency checks passed."
RUBY
