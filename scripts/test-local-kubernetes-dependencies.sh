#!/usr/bin/env bash

set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd -- "$script_dir/.." && pwd)"
rendered="$(mktemp "${TMPDIR:-/tmp}/simplematch-local-dependencies.XXXXXX.yaml")"
trap 'rm -f "$rendered"' EXIT

command -v kubectl >/dev/null 2>&1 || { printf '%s\n' 'kubectl is required.' >&2; exit 1; }
kubectl kustomize "$repo_root/deploy/k8s/overlays/local" --load-restrictor LoadRestrictionsNone >"$rendered"

ruby - "$rendered" <<'RUBY'
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

require_value(kafka.fetch("spec").fetch("replicas") == 3, "Kafka must have three broker/controller replicas")
require_value(kafka_spec.dig("nodeSelector", "simplematch.io/node-pool") == "local-resilience", "Kafka must use local-resilience workers")
require_value(kafka_claims.length == 1 && kafka_claims.fetch(0).dig("spec", "storageClassName") == "simplematch-rwo-pod", "Kafka must use one local PVC per ordinal")
require_value(kafka_pdb.dig("spec", "minAvailable") == 2, "Kafka PDB must retain two brokers")
require_value(kafka_container&.dig("resources", "requests") && kafka_container.dig("resources", "limits"), "Kafka must define resources")
require_value(kafka_container&.key?("readinessProbe") && kafka_container.key?("startupProbe"), "Kafka must define startup and readiness probes")
require_value(kafka_service.dig("spec", "ports", 0, "port") == 9092, "Kafka bootstrap Service must expose 9092")
require_value(kafka_headless.dig("spec", "clusterIP") == "None", "Kafka broker DNS must use a headless Service")
require_value(topic_job.dig("spec", "activeDeadlineSeconds") == 300, "Kafka topic provisioning must be bounded")
topic_container = topic_job.dig("spec", "template", "spec", "containers", 0)
require_value(topic_container&.dig("resources", "requests") && topic_container.dig("resources", "limits"), "Kafka topic provisioning must define resources")

connect = resources.fetch(["Deployment", "kafka-connect"])
connect_spec = connect.fetch("spec").fetch("template").fetch("spec")
connect_container = connect_spec.fetch("containers").find { |container| container.fetch("name") == "kafka-connect" }
connect_service = resources.fetch(["Service", "kafka-connect"])
connect_environment = connect_container.fetch("env").to_h { |entry| [entry.fetch("name"), entry] }
connect_config = resources.fetch(["ConfigMap", "simplematch-kafka-connect-config"])

require_value(connect.fetch("spec").fetch("replicas") == 2, "Local Kafka Connect must retain two workers")
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
  connect_environment.dig("CONFIG_STORAGE_REPLICATION_FACTOR", "value") == "3" &&
    connect_environment.dig("OFFSET_STORAGE_REPLICATION_FACTOR", "value") == "3" &&
    connect_environment.dig("STATUS_STORAGE_REPLICATION_FACTOR", "value") == "3",
  "Local Kafka Connect internal topics must use replication factor three"
)
require_value(
  connect_environment.dig("RISK_SERVICE_POSTGRES_USER", "valueFrom", "secretKeyRef", "name") == "simplematch-postgres-secrets" &&
    connect_environment.dig("RISK_SERVICE_POSTGRES_PASSWORD", "valueFrom", "secretKeyRef", "name") == "simplematch-postgres-secrets",
  "Local Kafka Connect must receive Risk database credentials from the local Secret"
)
require_value(connect_container&.dig("resources", "requests") && connect_container.dig("resources", "limits"), "Local Kafka Connect must define resources")
require_value(connect_container&.key?("readinessProbe") && connect_container.key?("startupProbe"), "Local Kafka Connect must define bounded probes")
require_value(connect_service.dig("spec", "ports", 0, "port") == 8083, "Kafka Connect Service must expose 8083")
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
end

require_value(!resources.key?(["NetworkPolicy", "simplematch-java-services-local-bridge"]), "local workloads must not depend on the old Compose bridge")
puts "Local Kubernetes dependency checks passed."
RUBY
