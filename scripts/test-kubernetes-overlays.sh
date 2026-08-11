#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
temporary_directory="$(mktemp -d "${TMPDIR:-/tmp}/simplematch-kustomize.XXXXXX")"
trap 'rm -rf "$temporary_directory"' EXIT

for overlay in local test staging production; do
  rendered="$temporary_directory/$overlay.yaml"
  kubectl kustomize "$repo_root/deploy/k8s/overlays/$overlay" \
    --load-restrictor LoadRestrictionsNone >"$rendered"
  ruby - "$rendered" "$overlay" <<'RUBY'
require "yaml"

rendered_path, overlay = ARGV
visitor = Psych::Visitors::ToRuby.create
documents = Psych.parse_stream(File.read(rendered_path, encoding: "UTF-8")).children.map { |document| visitor.accept(document) }.compact
resources = documents.to_h { |document| [[document.fetch("kind"), document.fetch("metadata").fetch("name")], document] }

required_deployments = %w[
  account-service
  risk-service
  persistence
  market-data-projection
  marketdata-publisher
  query-service
]
required_deployments.each do |name|
  deployment = resources.fetch(["Deployment", name])
  pod = deployment.fetch("spec").fetch("template")
  container = pod.fetch("spec").fetch("containers").first
  abort "#{overlay}: #{name} has no service account" unless pod.fetch("spec").key?("serviceAccountName")
  abort "#{overlay}: #{name} has no readiness probe" unless container.key?("readinessProbe")
  abort "#{overlay}: #{name} has no startup probe" unless container.key?("startupProbe")
  abort "#{overlay}: #{name} has no liveness probe" unless container.key?("livenessProbe")
  security = container.fetch("securityContext")
  abort "#{overlay}: #{name} is not non-root" unless security.fetch("runAsNonRoot")
  abort "#{overlay}: #{name} is not read-only root" unless security.fetch("readOnlyRootFilesystem")
end

network_policy = resources.fetch(["NetworkPolicy", "simplematch-java-services"])
network_policy.fetch("spec").fetch("ingress").each do |rule|
  rule.fetch("from", []).each do |source|
    abort "#{overlay}: NetworkPolicy contains an unrestricted ingress pod selector" if source["podSelector"] == {}
  end
end
network_policy.fetch("spec").fetch("egress").each do |rule|
  rule.fetch("to", []).each do |destination|
    abort "#{overlay}: NetworkPolicy contains an unrestricted egress pod selector" if destination["podSelector"] == {}
  end
end

%w[account-service risk-service persistence market-data-projection marketdata-publisher query-service].each do |name|
  config = resources.fetch(["ConfigMap", "#{name}-config"], nil)
  next unless config
  application = config.fetch("data").fetch("application.yaml")
  abort "#{overlay}: #{name} ConfigMap contains postgres.dsn" if application.include?("postgres.dsn")
  abort "#{overlay}: #{name} ConfigMap contains a password" if application.match?(/password|sasl\.jaas/i)
end

if %w[staging production].include?(overlay)
  required_deployments.each do |name|
    image = resources.fetch(["Deployment", name]).fetch("spec").fetch("template").fetch("spec").fetch("containers").first.fetch("image")
    abort "#{overlay}: #{name} image is not digest pinned" unless image.match?(/@sha256:[0-9a-f]{64}\z/)
    pod_spec = resources.fetch(["Deployment", name]).fetch("spec").fetch("template").fetch("spec")
    container = pod_spec.fetch("containers").first
    postgres_dsn = container.fetch("env").find { |entry| entry["name"] == "SIMPLEMATCH_POSTGRES_DSN" }
    abort "#{overlay}: #{name} has no secret-backed PostgreSQL DSN" unless postgres_dsn&.dig("valueFrom", "secretKeyRef")
    postgres_mount = container.fetch("volumeMounts").find { |entry| entry["name"] == "postgres-tls" }
    abort "#{overlay}: #{name} has no PostgreSQL CA mount" unless postgres_mount&.fetch("mountPath") == "/etc/simplematch/postgres-tls"
    postgres_volume = pod_spec.fetch("volumes").find { |entry| entry["name"] == "postgres-tls" }
    postgres_secret = postgres_volume&.dig("secret")
    abort "#{overlay}: #{name} does not require the PostgreSQL CA Secret" unless postgres_secret&.fetch("secretName") == "simplematch-postgres-tls" && postgres_secret.fetch("optional") == false
  end
  %w[account-service risk-service persistence market-data-projection query-service].each do |name|
    env = resources.fetch(["Deployment", name]).fetch("spec").fetch("template").fetch("spec").fetch("containers").first.fetch("env")
    protocol = env.find { |entry| entry["name"] == "SPRING_KAFKA_PROPERTIES_SECURITY_PROTOCOL" }
    abort "#{overlay}: #{name} does not require SASL_SSL" unless protocol.fetch("value") == "SASL_SSL"
  end
  %w[account-service risk-service].each do |name|
    env = resources.fetch(["Deployment", name]).fetch("spec").fetch("template").fetch("spec").fetch("containers").first.fetch("env")
    tls = env.find { |entry| entry["name"] == "SIMPLEMATCH_GRPC_SECURITY_TLS_ENABLED" }
    abort "#{overlay}: #{name} does not require gRPC TLS" unless tls.fetch("value") == "true"
  end
  abort "#{overlay}: external NetworkPolicy missing" unless resources.key?(["NetworkPolicy", "simplematch-java-services-external"])
end

puts "Validated #{overlay}: #{documents.length} Kubernetes resources"
RUBY
done
