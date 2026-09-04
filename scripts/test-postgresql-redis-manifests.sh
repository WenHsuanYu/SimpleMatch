#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd -- "$script_dir/.." && pwd)"

command -v ruby >/dev/null 2>&1 || {
  printf '%s\n' 'ruby is required.' >&2
  exit 1
}

ruby -ryaml - "$repo_root/deploy/k8s/postgresql.yaml" "$repo_root/deploy/k8s/redis.yaml" <<'RUBY'
postgresql_path, redis_path = ARGV

def abort_unless(condition, message)
  abort message unless condition
end

def labels_include?(resource, expected)
  labels = resource.dig("metadata", "labels") || {}
  expected.all? { |key, value| labels[key] == value }
end

def assert_labels(resource, expected, name)
  abort_unless(labels_include?(resource, expected), "#{name} labels are incomplete")
end

def assert_probe(container, probe_name, executable, name)
  probe = container.fetch(probe_name, {})
  command = probe.dig("exec", "command")
  abort_unless(command&.first == executable, "#{name} #{probe_name} must execute #{executable}")
  abort_unless(probe.fetch("periodSeconds") > 0, "#{name} #{probe_name} has no bounded period")
  abort_unless(probe.fetch("timeoutSeconds") > 0, "#{name} #{probe_name} has no bounded timeout")
  abort_unless(probe.fetch("failureThreshold") > 0, "#{name} #{probe_name} has no bounded failure threshold")
  abort_unless(
    probe.fetch("periodSeconds") * probe.fetch("failureThreshold") <= 300,
    "#{name} #{probe_name} exceeds the bounded five-minute probe window"
  )
end

documents = [postgresql_path, redis_path].flat_map do |path|
  YAML.load_stream(File.read(path, encoding: "UTF-8"))
end.compact
resources = documents.to_h do |document|
  [[document.fetch("kind"), document.fetch("metadata").fetch("name")], document]
end

postgres_labels = {
  "app.kubernetes.io/name" => "postgres",
  "app.kubernetes.io/component" => "database",
  "app.kubernetes.io/part-of" => "simplematch"
}
redis_labels = {
  "app.kubernetes.io/name" => "redis",
  "app.kubernetes.io/component" => "cache",
  "app.kubernetes.io/part-of" => "simplematch"
}

postgres_service = resources.fetch(["Service", "postgres"])
postgres_stateful_set = resources.fetch(["StatefulSet", "postgres"])
postgres_pdb = resources.fetch(["PodDisruptionBudget", "postgres"])
postgres_pod = postgres_stateful_set.fetch("spec").fetch("template")
postgres_spec = postgres_pod.fetch("spec")
postgres_container = postgres_spec.fetch("containers").fetch(0)

[postgres_service, postgres_stateful_set, postgres_pdb].each do |resource|
  assert_labels(resource, postgres_labels, "PostgreSQL #{resource.fetch("kind")}")
end
assert_labels(postgres_pod, postgres_labels, "PostgreSQL Pod")
assert_labels(postgres_stateful_set.fetch("spec").fetch("volumeClaimTemplates").fetch(0), postgres_labels, "PostgreSQL PVC")

abort_unless(postgres_stateful_set.dig("spec", "replicas") == 1, "PostgreSQL must remain a singleton")
abort_unless(postgres_stateful_set.dig("spec", "serviceName") == "postgres", "PostgreSQL StatefulSet must use Service postgres")
abort_unless(
  postgres_spec.dig("nodeSelector", "simplematch.io/node-pool") == "local-resilience" &&
    postgres_spec.dig("nodeSelector", "simplematch.io/worker-slot") == "0",
  "PostgreSQL must select local worker slot 0"
)
abort_unless(postgres_spec["affinity"].nil?, "PostgreSQL must not claim portable or cross-node placement")
abort_unless(postgres_spec["topologySpreadConstraints"].nil?, "PostgreSQL must not claim cross-node spreading")

postgres_selector = postgres_stateful_set.dig("spec", "selector", "matchLabels")
abort_unless(postgres_selector == postgres_labels, "PostgreSQL StatefulSet selector is not canonical")
abort_unless(postgres_service.dig("spec", "selector") == postgres_labels, "postgres Service selector is not canonical")
abort_unless(postgres_service.dig("spec", "clusterIP") == "None", "postgres Service must be headless")
abort_unless(postgres_service.dig("spec", "publishNotReadyAddresses") == false, "postgres Service must wait for readiness")
abort_unless(postgres_service.dig("spec", "ports", 0, "port") == 5432, "postgres Service must expose port 5432")

claims = postgres_stateful_set.dig("spec", "volumeClaimTemplates")
abort_unless(claims&.length == 1, "PostgreSQL must have exactly one PVC template")
claim = claims.fetch(0)
abort_unless(claim.fetch("metadata").fetch("name") == "postgres-data", "PostgreSQL PVC must be named postgres-data")
abort_unless(claim.dig("spec", "storageClassName") == "simplematch-rwo-pod", "PostgreSQL must use the node-local StorageClass")
abort_unless(claim.dig("spec", "accessModes") == ["ReadWriteOnce"], "PostgreSQL PVC must be single-writer")
abort_unless(claim.dig("spec", "resources", "requests", "storage") == "10Gi", "PostgreSQL PVC size must be explicit")

postgres_env = postgres_container.fetch("env").to_h { |entry| [entry.fetch("name"), entry] }
abort_unless(postgres_env.dig("POSTGRES_DB", "value") == "simplematch", "PostgreSQL database name is not canonical")
abort_unless(postgres_env.dig("POSTGRES_USER", "value") == "simplematch", "PostgreSQL user is not canonical")
password_ref = postgres_env.dig("POSTGRES_PASSWORD", "valueFrom", "secretKeyRef")
abort_unless(
  password_ref == {"name" => "simplematch-postgres-secrets", "key" => "postgres_password", "optional" => false},
  "PostgreSQL password must come from the external Secret contract"
)
postgres_start_arguments = Array(postgres_container["command"]) + Array(postgres_container["args"])
abort_unless(postgres_start_arguments.include?("wal_level=logical"), "PostgreSQL must enable logical WAL")

postgres_resources = postgres_container.fetch("resources")
abort_unless(
  postgres_resources == {
    "requests" => {"cpu" => "500m", "memory" => "1Gi"},
    "limits" => {"cpu" => "1", "memory" => "2Gi"}
  },
  "PostgreSQL resources must remain explicitly bounded"
)
%w[startupProbe readinessProbe livenessProbe].each do |probe_name|
  assert_probe(postgres_container, probe_name, "pg_isready", "PostgreSQL")
end
abort_unless(
  postgres_container.dig("readinessProbe", "exec", "command") ==
    ["pg_isready", "-h", "127.0.0.1", "-p", "5432", "-U", "simplematch", "-d", "simplematch"],
  "PostgreSQL readiness must check the local database connection"
)
abort_unless(postgres_pdb.dig("spec", "minAvailable") == 1, "PostgreSQL PDB must require one available Pod")
abort_unless(postgres_pdb.dig("spec", "selector", "matchLabels") == postgres_labels, "PostgreSQL PDB selector is not canonical")

redis_service = resources.fetch(["Service", "redis"])
redis_deployment = resources.fetch(["Deployment", "redis"])
redis_pod = redis_deployment.fetch("spec").fetch("template")
redis_spec = redis_pod.fetch("spec")
redis_container = redis_spec.fetch("containers").fetch(0)

[redis_service, redis_deployment].each do |resource|
  assert_labels(resource, redis_labels, "Redis #{resource.fetch("kind")}")
end
assert_labels(redis_pod, redis_labels, "Redis Pod")

abort_unless(redis_deployment.dig("spec", "replicas") == 1, "Redis must remain a singleton")
abort_unless(redis_deployment.dig("spec", "strategy", "type") == "Recreate", "Redis must use a singleton rollout")
abort_unless(
  redis_spec.dig("nodeSelector", "simplematch.io/node-pool") == "local-resilience" &&
    redis_spec.dig("nodeSelector", "simplematch.io/worker-slot").nil?,
  "Redis must remain portable across local resilience worker slots"
)
preferred_slot = redis_spec.dig("affinity", "nodeAffinity", "preferredDuringSchedulingIgnoredDuringExecution")
abort_unless(
  preferred_slot&.any? { |entry| entry.dig("preference", "matchExpressions").any? { |expression| expression == {"key" => "simplematch.io/worker-slot", "operator" => "In", "values" => ["2"]} } },
  "Redis must prefer portable worker slot 2 without requiring it"
)

redis_selector = redis_deployment.dig("spec", "selector", "matchLabels")
abort_unless(redis_selector == redis_labels, "Redis Deployment selector is not canonical")
abort_unless(redis_service.dig("spec", "selector") == redis_labels, "redis Service selector is not canonical")
abort_unless(redis_service.dig("spec", "ports", 0, "port") == 6379, "redis Service must expose port 6379")

toleration = redis_spec.fetch("tolerations").find { |entry| entry["key"] == "simplematch.io/portable-workload" }
abort_unless(
  toleration == {
    "key" => "simplematch.io/portable-workload",
    "operator" => "Exists",
    "effect" => "NoExecute",
    "tolerationSeconds" => 30
  },
  "Redis must use the accepted 30-second portable-workload toleration"
)
%w[node.kubernetes.io/not-ready node.kubernetes.io/unreachable].each do |key|
  abort_unless(
    redis_spec.fetch("tolerations").include?(
      {
        "key" => key,
        "operator" => "Exists",
        "effect" => "NoExecute",
        "tolerationSeconds" => 30
      }
    ),
    "Redis must tolerate the 30-second Kubernetes #{key} taint"
  )
end

redis_resources = redis_container.fetch("resources")
abort_unless(
  redis_resources == {
    "requests" => {"cpu" => "100m", "memory" => "128Mi"},
    "limits" => {"cpu" => "500m", "memory" => "512Mi"}
  },
  "Redis resources must remain explicitly bounded"
)
%w[startupProbe readinessProbe livenessProbe].each do |probe_name|
  assert_probe(redis_container, probe_name, "redis-cli", "Redis")
end
abort_unless(redis_container.dig("readinessProbe", "exec", "command") == ["redis-cli", "ping"], "Redis readiness must use PING")
abort_unless(redis_spec.dig("volumes").any? { |volume| volume == {"name" => "redis-data", "emptyDir" => {}} }, "Redis state must be disposable emptyDir")
abort_unless(redis_spec.dig("volumes").none? { |volume| volume.key?("persistentVolumeClaim") }, "Redis must not use a PVC")
redis_pdb = resources.find do |(kind, _), resource|
  kind == "PodDisruptionBudget" &&
    (resource.dig("metadata", "name") == "redis" || resource.dig("spec", "selector", "matchLabels", "app.kubernetes.io/component") == "cache")
end
abort_unless(redis_pdb.nil?, "Redis manifest must not define a PDB")

puts "PostgreSQL and Redis Kubernetes manifest contracts passed."
RUBY
