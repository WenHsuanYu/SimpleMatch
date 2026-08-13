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
java_workloads.each do |name|
  deployment = resources.fetch(["Deployment", name])
  pod = deployment.fetch("spec").fetch("template")
  spec = pod.fetch("spec")
  container = spec.fetch("containers").first
  abort "#{name} must have two replicas" unless deployment.dig("spec", "replicas") == 2
  abort "#{name} must select local-resilience workers" unless spec.dig("nodeSelector", "simplematch.io/node-pool") == "local-resilience"
  spread = spec.fetch("topologySpreadConstraints", []).first
  abort "#{name} must spread by hostname" unless spread && spread["topologyKey"] == "kubernetes.io/hostname" && spread["maxSkew"] == 1 && spread["whenUnsatisfiable"] == "DoNotSchedule"
  %w[startupProbe readinessProbe livenessProbe].each do |probe_name|
    probe = container.fetch(probe_name, {})
    http_get = probe.fetch("httpGet", {})
    abort "#{name} #{probe_name} must use an HTTP health endpoint" unless http_get["path"] && http_get["port"]
    expected_path = probe_name == "livenessProbe" ? "/actuator/health/liveness" : "/actuator/health/readiness"
    abort "#{name} #{probe_name} must use #{expected_path}" unless http_get["path"] == expected_path
  end
  abort "#{name} must define resources" unless container.dig("resources", "requests") && container.dig("resources", "limits")
  abort "#{name} must have a PDB" unless resources.key?(["PodDisruptionBudget", name])
end

streamer = resources.fetch(["Deployment", "marketdata-streamer"])
abort "marketdata-streamer must use Recreate" unless streamer.dig("spec", "strategy", "type") == "Recreate"
abort "QuickFIX must have a sole-owner PDB" unless resources.key?(["PodDisruptionBudget", "quickfix-gateway"])

flyway_services = %w[account-service market-data-projection marketdata-publisher persistence query-service quickfix-gateway risk-service]
flyway_services.each do |name|
  job = resources.fetch(["Job", "#{name}-flyway"])
  container = job.dig("spec", "template", "spec", "containers", 0)
  abort "#{name} Flyway Job must be resource limited" unless container.dig("resources", "requests") && container.dig("resources", "limits")
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
