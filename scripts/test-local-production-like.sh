#!/usr/bin/env bash

set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd -- "$script_dir/.." && pwd)"

for version_contract in \
  'gradle-9.7.0-bin.zip' \
  'spring-boot = "4.1.0"' \
  'postgres:18.4' \
  'redis:8.8.1-alpine' \
  'apache/kafka:4.3.1' \
  'quay.io/debezium/connect:3.6.0.Final' \
  'ubuntu:26.04' \
  'VCPKG_VERSION=2026.07.29'; do
  grep -R -Fq "$version_contract" \
    "$repo_root/gradle/wrapper/gradle-wrapper.properties" \
    "$repo_root/gradle/libs.versions.toml" \
    "$repo_root/deploy/compose" \
    "$repo_root/deploy/docker" \
    "$repo_root/.github/workflows" \
    "$repo_root/deploy/k8s" 2>/dev/null || {
    echo "Latest-version contract is missing $version_contract." >&2
    exit 1
  }
done

image_list="$($script_dir/build-local-images.sh --list)"
expected_images=(
  "simplematch/account-service:local"
  "simplematch/risk-service:local"
  "simplematch/persistence:local"
  "simplematch/market-data-projection:local"
  "simplematch/marketdata-publisher:local"
  "simplematch/query-service:local"
  "simplematch/flyway-runner:local"
  "simplematch-matching:local"
  "quickfix-gateway:local"
)

for image in "${expected_images[@]}"; do
  grep -Fq "$image" <<<"$image_list" || {
    echo "Local image inventory is missing $image." >&2
    exit 1
  }
done

dry_run="$($script_dir/build-local-images.sh --dry-run)"
grep -Fq 'bootBuildImage' <<<"$dry_run" || {
  echo "Local image dry-run does not include Spring Boot image builds." >&2
  exit 1
}
grep -Fq 'Dockerfile.matching' <<<"$dry_run" || {
  echo "Local image dry-run does not include the native Matching Dockerfile." >&2
  exit 1
}
grep -Fq 'Dockerfile.flyway-runner' <<<"$dry_run" || {
  echo "Local image dry-run does not include the Flyway runner Dockerfile." >&2
  exit 1
}

"$script_dir/run-local-production-like-certification.sh" --help >/dev/null
certification_dry_run="$($script_dir/run-local-production-like-certification.sh \
  --dry-run --skip-build --skip-compose --skip-kubernetes)"
grep -Fq 'test-kubernetes-overlays.sh' <<<"$certification_dry_run" || {
  echo "Local certification dry-run does not include Kubernetes overlay validation." >&2
  exit 1
}
grep -Fq 'test-matching-topic-profile.sh' <<<"$certification_dry_run" || {
  echo "Local certification dry-run does not include the Kafka profile contract." >&2
  exit 1
}

ruby -r yaml -e 'YAML.load_file(ARGV.fetch(0), aliases: true); puts "valid YAML: #{ARGV.fetch(0)}"' \
  "$repo_root/deploy/compose/kafka-connect.production-like.yml" >/dev/null

echo "Local production-like image and certification contracts are valid."
