#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
account_config="$repo_root/deploy/k8s/base/java-services-configmaps.yaml"
matching_provisioner="$repo_root/scripts/provision-matching-topics.sh"
production_like_runner="$repo_root/scripts/run-local-production-like-certification.sh"

for path in "$account_config" "$matching_provisioner" "$production_like_runner"; do
  [[ -f "$path" ]] || {
    printf 'Matching topic cutover input does not exist: %s\n' "$path" >&2
    exit 1
  }
done

ruby -ryaml - "$account_config" <<'RUBY'
documents = YAML.load_stream(File.read(ARGV.fetch(0), encoding: "UTF-8")).compact
account = documents.find do |document|
  document["kind"] == "ConfigMap" && document.dig("metadata", "name") == "account-service-config"
end
abort "account-service-config is missing" if account.nil?
application = YAML.safe_load(account.fetch("data").fetch("application.yaml"))
config = application.fetch("simplematch").fetch("account-service")
legacy = config.dig("lifecycle-consumer", "enabled")
abort "production-shaped Account must not enable the legacy matching.executions consumer" if legacy == true
abort "production-shaped Account must enable the final matching.events consumer" unless
  config.dig("final-matching-events", "enabled") == true
RUBY

for topic in matching.commands matching.events; do
  grep -Fq "$topic" "$matching_provisioner" || {
    printf 'Canonical Matching topic provisioner is missing %s.\n' "$topic" >&2
    exit 1
  }
done
if grep -Fq 'matching.executions' "$matching_provisioner"; then
  printf '%s\n' 'Canonical Matching topic provisioner must not provision matching.executions.' >&2
  exit 1
fi

create_topics_block="$(awk '
  /^create_kafka_topics\(\) \{/ { inside=1 }
  inside { print }
  inside && /^}$/ { exit }
' "$production_like_runner")"
[[ -n "$create_topics_block" ]] || {
  printf '%s\n' 'Production-like runner has no create_kafka_topics function.' >&2
  exit 1
}
for topic in matching.commands matching.events; do
  grep -Fq "$topic" <<<"$create_topics_block" || {
    printf 'Production-like topic bootstrap is missing %s.\n' "$topic" >&2
    exit 1
  }
done
if grep -Fq 'matching.executions' <<<"$create_topics_block"; then
  printf '%s\n' 'Production-like topic bootstrap must not provision legacy matching.executions.' >&2
  exit 1
fi

printf '%s\n' 'Matching topic cutover contract passed.'
