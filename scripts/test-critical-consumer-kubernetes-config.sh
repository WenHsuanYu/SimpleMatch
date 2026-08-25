#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
restart_certification="$repo_root/scripts/run-critical-consumer-restart-certification.sh"

ruby -r yaml - \
  "$repo_root/deploy/k8s/base/java-services-configmaps.yaml" \
  "$repo_root/deploy/k8s/quickfix-gateway-configmap.yaml" <<'RUBY'
paths = ARGV
resources = {}

paths.each do |path|
  YAML.load_stream(File.read(path, encoding: "UTF-8")).compact.each do |document|
    next unless document["kind"] == "ConfigMap"

    resources[document.fetch("metadata").fetch("name")] = document
  end
end

expected = {
  "account-service-config" => %w[simplematch account-service final-matching-events enabled],
  "persistence-config" => %w[simplematch persistence matching-events enabled],
  "quickfix-gateway-config" => %w[simplematch quickfix-gateway final-matching-events enabled]
}

def value_at(root, path)
  current = root
  path.each do |key|
    return [false, nil] unless current.is_a?(Hash) && current.key?(key)

    current = current.fetch(key)
  end
  [true, current]
end

expected.each do |config_name, path|
  config = resources[config_name]
  abort "#{config_name}: required ConfigMap is missing" unless config

  application_yaml = config.dig("data", "application.yaml")
  abort "#{config_name}: data.application.yaml is missing" unless application_yaml

  application = YAML.safe_load(application_yaml, aliases: true)
  present, value = value_at(application, path)
  setting = path.join(".")
  abort "#{config_name}: #{setting} must be true" unless present && value == true
end
RUBY

[[ -x "$restart_certification" ]] || {
  printf '%s\n' 'Critical consumer restart certification must be executable.' >&2
  exit 1
}
bash -n "$restart_certification"
"$restart_certification" --help >/dev/null

grep -Fq 'simplematch_kind_namespace_is_disposable' "$restart_certification"
grep -Fq 'rollout restart deployment/account-service deployment/persistence' "$restart_certification"
grep -Fq 'deployment/quickfix-gateway' "$restart_certification"
grep -Fq 'delete pod "$postgres"' "$restart_certification"
grep -Fq 'delete pod "$broker"' "$restart_certification"
grep -Fq 'notProven' "$restart_certification"

printf '%s\n' 'Critical Matching Event consumer deployment and restart contracts are valid.'
