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
  "simplematch/marketdata-streamer:local"
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

grep -Fq 'COPY --from=source / /' "$repo_root/deploy/docker/Dockerfile.kind-normalized" || {
  echo "Kind image normalizer does not flatten the local transfer image." >&2
  exit 1
}

bash -n "$repo_root/scripts/normalize-local-images-for-kind.sh"
grep -Fq 'bootBuildImage source retained' "$repo_root/scripts/normalize-local-images-for-kind.sh" || {
  echo "Kind image normalizer does not retain the bootBuildImage source." >&2
  exit 1
}

"$script_dir/run-local-production-like-certification.sh" --help >/dev/null
grep -Fq 'SIMPLEMATCH_CERTIFICATION_TRADING_DAY' \
  "$repo_root/scripts/run-local-production-like-certification.sh" || {
  echo "Local certification does not support an explicit approved trading day." >&2
  exit 1
}
grep -Fq 'market-reference-${trading_day//-/}' \
  "$repo_root/scripts/run-local-production-like-certification.sh" || {
  echo "Local certification does not verify the approved artifact trading day." >&2
  exit 1
}
grep -Fq 'kubectl -n "$namespace" create -f "$artifact_manifest"' \
  "$repo_root/scripts/run-local-production-like-certification.sh" || {
  echo "Local certification must create the immutable artifact without a large apply annotation." >&2
  exit 1
}
grep -Fq 'create_certification_namespace' \
  "$repo_root/scripts/run-local-production-like-certification.sh" &&
grep -Fq 'Certification namespace already exists' \
  "$repo_root/scripts/run-local-production-like-certification.sh" || {
  echo "Local certification does not own and clean up its generated namespace safely." >&2
  exit 1
}
certification_dry_run="$($script_dir/run-local-production-like-certification.sh \
  --dry-run --skip-build --skip-compose)"
grep -Fq 'test-kubernetes-overlays.sh' <<<"$certification_dry_run" || {
  echo "Local certification dry-run does not include Kubernetes overlay validation." >&2
  exit 1
}
grep -Fq 'test-matching-topic-profile.sh' <<<"$certification_dry_run" || {
  echo "Local certification dry-run does not include the Kafka profile contract." >&2
  exit 1
}
grep -Fq 'normalize-local-images-for-kind.sh' <<<"$certification_dry_run" || {
  echo "Local certification dry-run does not include kind image normalization." >&2
  exit 1
}
grep -Fq 'apply_kubernetes_migrations' \
  "$repo_root/scripts/run-local-production-like-certification.sh" &&
grep -Fq 'local-kubernetes-migrations.yaml' \
  "$repo_root/scripts/run-local-production-like-certification.sh" &&
grep -Fq 'local-kubernetes-workloads.yaml' \
  "$repo_root/scripts/run-local-production-like-certification.sh" || {
  echo "Local certification does not sequence migrations before runtime workloads." >&2
  exit 1
}
grep -Fq 'local-kubernetes-inputs.yaml' \
  "$repo_root/scripts/run-local-production-like-certification.sh" || {
  echo "Local certification does not isolate large immutable Kubernetes inputs." >&2
  exit 1
}
grep -Fq 'range .IPAM.Config' \
  "$repo_root/scripts/run-local-production-like-certification.sh" || {
  echo "Local certification does not select an IPv4 Docker bridge subnet." >&2
  exit 1
}

for required_topic in matching.executions account.lifecycle marketdata.events; do
  grep -Fq "$required_topic" "$repo_root/scripts/run-local-production-like-certification.sh" || {
    echo "Local certification does not provision required topic $required_topic." >&2
    exit 1
  }
done

bash "$repo_root/scripts/validate-matching-producer-contract.sh"
"$repo_root/scripts/run-matching-kafka-failure-check.sh" --help >/dev/null

grep -Fq 'publish_local_matching_open_barriers' \
  "$repo_root/scripts/run-local-production-like-certification.sh" || {
  echo "Local certification does not publish the required Matching Open Barriers." >&2
  exit 1
}
grep -Fq 'kubernetes-open-barriers' \
  "$repo_root/scripts/run-local-production-like-certification.sh" || {
  echo "Local certification does not retain Open Barrier publication evidence." >&2
  exit 1
}
grep -Fq -- '--matching-fleet-only' \
  "$repo_root/scripts/run-local-production-like-certification.sh" || {
  echo "Local certification does not support a clean Matching-only retest." >&2
  exit 1
}
grep -Fq 'select_matching_workload' \
  "$repo_root/scripts/run-local-production-like-certification.sh" || {
  echo "Matching-only retest does not isolate a fresh Matching StatefulSet." >&2
  exit 1
}
grep -Fq -- '--allow-local-image' \
  "$repo_root/scripts/run-local-production-like-certification.sh" || {
  echo "Local certification does not declare its local-image fleet verification mode." >&2
  exit 1
}
grep -Fq 'trading_session_id=${trading_day}-regular' \
  "$repo_root/scripts/run-local-production-like-certification.sh" || {
  echo "Local certification does not use the artifact-derived regular session identity." >&2
  exit 1
}

for service in account-service risk-service persistence market-data-projection marketdata-publisher marketdata-streamer; do
  grep -Fq 'implementation("org.springframework.boot:spring-boot-starter-web")' \
    "$repo_root/services/$service/build.gradle.kts" || {
    echo "$service does not provide the HTTP management runtime required by Kubernetes probes." >&2
    exit 1
  }
done

ruby -r yaml -e 'YAML.load_file(ARGV.fetch(0), aliases: true); puts "valid YAML: #{ARGV.fetch(0)}"' \
  "$repo_root/deploy/compose/kafka-connect.production-like.yml" >/dev/null

echo "Local production-like image and certification contracts are valid."
