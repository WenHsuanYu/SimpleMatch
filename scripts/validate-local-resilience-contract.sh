#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd -- "$script_dir/.." && pwd)"
rendered="$(mktemp "${TMPDIR:-/tmp}/simplematch-resilience-rendered.XXXXXX.yaml")"
trap 'rm -f "$rendered"' EXIT

command -v kubectl >/dev/null 2>&1 || { printf '%s\n' 'kubectl is required.' >&2; exit 1; }
kubectl kustomize "$repo_root/deploy/k8s/overlays/local" --load-restrictor LoadRestrictionsNone >"$rendered"

ruby -ryaml - "$repo_root" "$rendered" <<'RUBY'
repo_root, rendered_path = ARGV
documents = YAML.load_stream(File.read(rendered_path)).compact
resources = documents.to_h { |document| [[document.fetch("kind"), document.dig("metadata", "name")], document] }

abort "canonical kind configuration is missing" unless File.file?(File.join(repo_root, "deploy/kind/simplematch-live.yaml"))
abort "canonical StorageClass manifest is missing" unless File.file?(File.join(repo_root, "deploy/kind/simplematch-live-storageclass.yaml"))

java_workloads = %w[account-service risk-service persistence market-data-projection query-service]
accepted_java_tolerations = [
  {"key" => "simplematch.io/portable-workload", "operator" => "Exists", "effect" => "NoExecute", "tolerationSeconds" => 30},
  {"key" => "node.kubernetes.io/not-ready", "operator" => "Exists", "effect" => "NoExecute", "tolerationSeconds" => 30},
  {"key" => "node.kubernetes.io/unreachable", "operator" => "Exists", "effect" => "NoExecute", "tolerationSeconds" => 30}
]
java_workloads.each do |name|
  deployment = resources.fetch(["Deployment", name])
  pod = deployment.fetch("spec").fetch("template")
  spec = pod.fetch("spec")
  container = spec.fetch("containers").first
  abort "#{name} must have two replicas" unless deployment.dig("spec", "replicas") == 2
  abort "#{name} must use the canonical local-resilience selector" unless
    spec.fetch("nodeSelector") == {"simplematch.io/node-pool" => "local-resilience"}
  spread = spec.fetch("topologySpreadConstraints", []).first
  expected_labels = {
    "app.kubernetes.io/name" => name,
    "app.kubernetes.io/component" => "java-service"
  }
  abort "#{name} must have one hostname spread constraint" unless
    spec.fetch("topologySpreadConstraints").length == 1 &&
      spread &&
      spread["topologyKey"] == "kubernetes.io/hostname" &&
      spread["maxSkew"] == 1 &&
      spread["whenUnsatisfiable"] == "DoNotSchedule" &&
      spread.dig("labelSelector", "matchLabels") == expected_labels
  abort "#{name} must use the accepted portable and node-loss tolerations" unless
    spec.fetch("tolerations").sort_by(&:to_s) == accepted_java_tolerations.sort_by(&:to_s)
  %w[startupProbe readinessProbe livenessProbe].each do |probe_name|
    probe = container.fetch(probe_name, {})
    http_get = probe.fetch("httpGet", {})
    abort "#{name} #{probe_name} must use an HTTP health endpoint" unless http_get["path"] && http_get["port"]
    expected_path = probe_name == "livenessProbe" ? "/actuator/health/liveness" : "/actuator/health/readiness"
    abort "#{name} #{probe_name} must use #{expected_path}" unless http_get["path"] == expected_path
    if name == "query-service" && probe_name == "livenessProbe"
      abort "query-service liveness probe timing is not the observed contract" unless
        probe["initialDelaySeconds"] == 20 &&
          probe["periodSeconds"] == 10 &&
          probe["failureThreshold"] == 3
    end
  end
  abort "#{name} must define resources" unless container.dig("resources", "requests") && container.dig("resources", "limits")
  pdb = resources.fetch(["PodDisruptionBudget", name], nil)
  abort "#{name} must have a PDB with minAvailable 1" unless pdb && pdb.dig("spec", "minAvailable") == 1
  abort "#{name} PDB must select its Java workload" unless pdb.dig("spec", "selector", "matchLabels") == expected_labels
end

matching = resources.fetch(["StatefulSet", "matching"])
matching_spec = matching.fetch("spec").fetch("template").fetch("spec")
matching_spread = matching_spec.fetch("topologySpreadConstraints", []).find do |constraint|
  constraint["topologyKey"] == "kubernetes.io/hostname"
end
abort "Matching local fleet must retain fifteen ordinal owners" unless matching.dig("spec", "replicas") == 15
abort "Matching local fleet must use bounded host spreading on local-resilience workers" unless
  matching_spec.dig("nodeSelector", "simplematch.io/node-pool") == "local-resilience" &&
    matching_spread &&
    matching_spread["maxSkew"] == 1 &&
    matching_spread["whenUnsatisfiable"] == "DoNotSchedule" &&
    matching_spread.dig("labelSelector", "matchLabels", "app.kubernetes.io/name") == "matching"

quickfix = resources.fetch(["StatefulSet", "quickfix-gateway"])
quickfix_spec = quickfix.fetch("spec").fetch("template").fetch("spec")
abort "QuickFIX must have one local slot-1 owner" unless
  quickfix.dig("spec", "replicas") == 1 &&
    quickfix_spec.dig("nodeSelector", "simplematch.io/node-pool") == "local-resilience" &&
    quickfix_spec.dig("nodeSelector", "simplematch.io/worker-slot") == "1"
quickfix_pdb = resources.fetch(["PodDisruptionBudget", "quickfix-gateway"], nil)
abort "QuickFIX must have a sole-owner PDB" unless
  quickfix_pdb &&
    quickfix_pdb.dig("spec", "minAvailable") == 1 &&
    quickfix_pdb.dig("spec", "selector", "matchLabels", "app.kubernetes.io/name") == "quickfix-gateway"
quickfix_owner_service = resources.fetch(["Service", "quickfix-gateway-owner-0"], nil)
abort "QuickFIX owner Service must select quickfix-gateway-0" unless
  quickfix_owner_service &&
    quickfix_owner_service.dig("spec", "selector", "app.kubernetes.io/name") == "quickfix-gateway" &&
    quickfix_owner_service.dig("spec", "selector", "statefulset.kubernetes.io/pod-name") == "quickfix-gateway-0"

streamer = resources.fetch(["Deployment", "marketdata-streamer"])
streamer_spec = streamer.fetch("spec").fetch("template").fetch("spec")
abort "marketdata-streamer must use one Recreate owner" unless
  streamer.dig("spec", "replicas") == 1 && streamer.dig("spec", "strategy", "type") == "Recreate"
abort "marketdata-streamer must remain portable within local-resilience workers" unless
  streamer_spec.dig("nodeSelector", "simplematch.io/node-pool") == "local-resilience" &&
    !streamer_spec.dig("nodeSelector", "simplematch.io/worker-slot") &&
    streamer_spec.fetch("tolerations", []).include?(
      "key" => "simplematch.io/portable-workload",
      "operator" => "Exists",
      "effect" => "NoExecute",
      "tolerationSeconds" => 30
    )

postgres = resources.fetch(["StatefulSet", "postgres"])
postgres_spec = postgres.dig("spec", "template", "spec")
postgres_container = postgres_spec.fetch("containers").find { |container| container.fetch("name") == "postgres" }
postgres_claim = postgres.dig("spec", "volumeClaimTemplates", 0)
abort "PostgreSQL must be a single local slot-0 StatefulSet" unless
  postgres.dig("spec", "replicas") == 1 &&
    postgres_spec.dig("nodeSelector", "simplematch.io/node-pool") == "local-resilience" &&
    postgres_spec.dig("nodeSelector", "simplematch.io/worker-slot") == "0"
abort "PostgreSQL must use the local RWO StorageClass" unless
  postgres_claim.dig("spec", "storageClassName") == "simplematch-rwo-pod" &&
    postgres_claim.dig("spec", "accessModes") == ["ReadWriteOnce"]
abort "PostgreSQL must have resources and readiness" unless
  postgres_container.dig("resources", "requests") &&
    postgres_container.dig("resources", "limits") &&
    postgres_container.key?("readinessProbe")
abort "PostgreSQL PDB must protect the sole replica" unless
  resources.dig(["PodDisruptionBudget", "postgres"], "spec", "minAvailable") == 1

redis = resources.fetch(["Deployment", "redis"])
redis_spec = redis.dig("spec", "template", "spec")
redis_container = redis_spec.fetch("containers").find { |container| container.fetch("name") == "redis" }
abort "Redis must be a portable singleton" unless
  redis.dig("spec", "replicas") == 1 &&
    redis_spec.dig("nodeSelector", "simplematch.io/node-pool") == "local-resilience" &&
    !redis_spec.dig("nodeSelector", "simplematch.io/worker-slot")
redis_tolerations = redis_spec.fetch("tolerations", [])
abort "Redis must use the accepted portable tolerations" unless
  [
    {"key" => "simplematch.io/portable-workload", "operator" => "Exists", "effect" => "NoExecute", "tolerationSeconds" => 30},
    {"key" => "node.kubernetes.io/not-ready", "operator" => "Exists", "effect" => "NoExecute", "tolerationSeconds" => 30},
    {"key" => "node.kubernetes.io/unreachable", "operator" => "Exists", "effect" => "NoExecute", "tolerationSeconds" => 30}
  ].all? { |expected| redis_tolerations.include?(expected) }
abort "Redis must not have a PDB" if resources.key?(["PodDisruptionBudget", "redis"])
abort "Redis must have resources and readiness" unless
  redis_container.dig("resources", "requests") &&
    redis_container.dig("resources", "limits") &&
    redis_container.key?("readinessProbe")

kafka = resources.fetch(["StatefulSet", "kafka"])
kafka_spec = kafka.dig("spec", "template", "spec")
kafka_container = kafka_spec.fetch("containers").find { |container| container.fetch("name") == "kafka" }
kafka_claim = kafka.dig("spec", "volumeClaimTemplates", 0)
abort "Kafka must have three local broker/controller replicas" unless
  kafka.dig("spec", "replicas") == 3 &&
    kafka_spec.dig("nodeSelector", "simplematch.io/node-pool") == "local-resilience"
abort "Kafka must have one local PVC per ordinal" unless
  kafka.dig("spec", "volumeClaimTemplates").length == 1 &&
    kafka_claim.dig("spec", "storageClassName") == "simplematch-rwo-pod"
abort "Kafka must have a two-available PDB" unless
  resources.dig(["PodDisruptionBudget", "kafka"], "spec", "minAvailable") == 2
abort "Kafka must have bounded resources and probes" unless
  kafka_container.dig("resources", "requests") &&
    kafka_container.dig("resources", "limits") &&
    kafka_container.key?("startupProbe") &&
    kafka_container.key?("readinessProbe")
abort "Kafka topic provisioning Job is missing" unless resources.key?(["Job", "kafka-topic-provisioning"])

flyway_services = %w[account-service market-data-projection persistence query-service quickfix-gateway risk-service]
flyway_services.each do |name|
  job = resources.fetch(["Job", "#{name}-flyway"])
  container = job.dig("spec", "template", "spec", "containers", 0)
  abort "#{name} Flyway Job must be resource limited" unless container.dig("resources", "requests") && container.dig("resources", "limits")
  abort "#{name} Flyway Job must have a bounded deadline" unless job.dig("spec", "activeDeadlineSeconds") == 300
  wait_container = job.dig("spec", "template", "spec", "initContainers", 0)
  abort "#{name} Flyway Job must wait for PostgreSQL" unless wait_container&.fetch("name") == "wait-for-postgres"
end

application_files = Dir[File.join(repo_root, "services", "**", "src", "main", "resources", "application.y*ml")]
application_files.each do |path|
  abort "#{path} must expose metrics" unless File.read(path).include?("include: health,info,metrics")
end

unsafe_log = Dir[File.join(repo_root, "services", "**", "src", "main", "java", "**", "*.java")].find do |path|
  text = File.read(path)
  text.match?(/(?:logger|LOGGER)\.(?:trace|debug|info|warn|error)\([^\n]*(?:msg=\{\}|message\)|raw[_ -]?fix|account[_ -]?payload)/i)
end
abort "application logs contain an unsafe payload template: #{unsafe_log}" if unsafe_log

puts "Local resilience contract target checks passed for #{java_workloads.length} replicated Java workloads."
RUBY
