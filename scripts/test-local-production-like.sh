#!/usr/bin/env bash
set -Eeuo pipefail
IFS=$'\n\t'

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd -- "$script_dir/.." && pwd)"
runner="$script_dir/run-local-production-like-certification.sh"
framework_lib="$script_dir/lib/local-certification-framework.sh"
kafka_lib="$script_dir/lib/local-certification-kafka.sh"
kubernetes_lib="$script_dir/lib/local-certification-kubernetes.sh"
connect_lib="$script_dir/lib/local-certification-connect.sh"
workloads_lib="$script_dir/lib/local-certification-workloads.sh"
bootstrap_lib="$script_dir/lib/local-certification-bootstrap.sh"
run_lib="$script_dir/lib/local-certification-run.sh"
transport_lib="$script_dir/lib/local-image-transport.sh"

for file in "$runner" "$framework_lib" "$kafka_lib" "$kubernetes_lib" "$connect_lib" \
  "$workloads_lib" "$bootstrap_lib" "$run_lib" "$transport_lib"; do
  bash -n "$file"
done

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
    printf 'Latest-version contract is missing %s.\n' "$version_contract" >&2
    exit 1
  }
done

image_list="$($script_dir/build-local-images.sh --list)"
expected_images=(
  'simplematch/account-service:local'
  'simplematch/risk-service:local'
  'simplematch/persistence:local'
  'simplematch/market-data-projection:local'
  'simplematch/marketdata-publisher:local'
  'simplematch/marketdata-streamer:local'
  'simplematch/query-service:local'
  'simplematch/flyway-runner:local'
  'simplematch-matching:local'
  'quickfix-gateway:local'
)
for image in "${expected_images[@]}"; do
  grep -Fq "$image" <<<"$image_list" || {
    printf 'Local image inventory is missing %s.\n' "$image" >&2
    exit 1
  }
done

dry_run="$($script_dir/build-local-images.sh --dry-run)"
grep -Fq 'bootBuildImage' <<<"$dry_run"
grep -Fq 'Dockerfile.matching' <<<"$dry_run"
grep -Fq 'Dockerfile.flyway-runner' <<<"$dry_run"
boot_override_dry_run="$(SIMPLEMATCH_BOOT_RUN_IMAGE=local/paketo-run:amd64 \
  "$script_dir/build-local-images.sh" --dry-run --service account-service)"
grep -Fq -- '--runImage=local/paketo-run:amd64' <<<"$boot_override_dry_run"
grep -Fq -- '--pullPolicy=IF_NOT_PRESENT' <<<"$boot_override_dry_run"
grep -Fq 'verify-local-boot-run-image.sh' <<<"$boot_override_dry_run"
bash -n "$repo_root/scripts/verify-local-boot-run-image.sh"
grep -Fq 'docker image save --platform' "$repo_root/scripts/verify-local-boot-run-image.sh"

# Registry-only transport is explicit: build, publish, digest-lock, render, pull.
bash -n "$script_dir/prepare-local-kubernetes-images.sh"
bash -n "$script_dir/publish-local-images.sh"
bash -n "$script_dir/render-local-kubernetes-manifest.sh"
grep -Fq 'publish-local-images.sh' "$script_dir/prepare-local-kubernetes-images.sh"
grep -Fq 'simplematch_local_image_lock_digest_reference' "$transport_lib"
grep -Fq 'docker push' "$script_dir/publish-local-images.sh"
grep -Fq 'digest: %s' "$script_dir/render-local-kubernetes-manifest.sh"
for removed_path in \
  "$repo_root/scripts/normalize-local-images-for-kind.sh" \
  "$repo_root/deploy/docker/Dockerfile.kind-normalized"; do
  [[ ! -e "$removed_path" ]] || {
    printf 'Removed direct-import artifact still exists: %s\n' "$removed_path" >&2
    exit 1
  }
done

"$runner" --help >/dev/null
grep -Fq 'SIMPLEMATCH_CERTIFICATION_TRADING_DAY' "$bootstrap_lib"
grep -Fq -- '--resume' "$bootstrap_lib"
grep -Fq 'phase_marker_directory' "$framework_lib" "$bootstrap_lib"
grep -Fq 'SIMPLEMATCH_CERTIFICATION_TIMEOUT_SECONDS' "$runner" "$framework_lib" "$bootstrap_lib"
grep -Fq 'SIMPLEMATCH_KAFKA_CAPACITY_WORKLOAD_FILE' "$runner"
grep -Fq 'scripts/testdata/matching-topic-profile/local/capacity.properties' "$runner"
grep -Fq 'workload.commands.per.day=10000' \
  "$repo_root/scripts/testdata/matching-topic-profile/local/capacity.properties"
grep -Fq 'workload.events.per.day=10000' \
  "$repo_root/scripts/testdata/matching-topic-profile/local/capacity.properties"

run_logged_function="$(sed -n '/^run_logged()/,/^run_refreshable_logged()/p' "$framework_lib")"
[[ "$(grep -Fc 'execute_with_certification_deadline "$@"' <<<"$run_logged_function")" == 1 ]] || {
  printf '%s\n' 'Local certification must execute each logged phase exactly once.' >&2
  exit 1
}

grep -Fq -- '--connect-timeout 5 --max-time 15' "$connect_lib"
grep -Fq 'SIMPLEMATCH_KAFKA_CONNECT_PROVIDER_RETRIES' "$connect_lib"
grep -Fq 'EnvVarConfigProvider is not ready; retrying registration' "$connect_lib"
grep -Fq 'Forwarding from 127\.0\.0\.1:([0-9]+)' "$connect_lib"
grep -Fq 'Unable to listen|error: unable to listen|address already in use' "$connect_lib"

grep -Fq 'market-reference-${trading_day//-/}' "$kubernetes_lib"
grep -Fq 'kubectl -n "$namespace" create -f "$artifact_manifest"' "$kubernetes_lib"
grep -Fq 'create_certification_namespace' "$kubernetes_lib"
grep -Fq 'Certification namespace already exists' "$kubernetes_lib"
grep -Fq 'simplematch_kind_namespace_is_disposable' "$bootstrap_lib"
grep -Fq 'simplematch_kind_delete_disposable_namespace' "$framework_lib"

certification_dry_run="$($runner --dry-run --skip-build --skip-compose)"
grep -Fq 'test-kubernetes-overlays.sh' <<<"$certification_dry_run"
grep -Fq 'test-local-kubernetes-dependencies.sh' <<<"$certification_dry_run"
grep -Fq 'test-matching-topic-profile.sh' <<<"$certification_dry_run"
grep -Fq 'prepare-local-kubernetes-images.sh' <<<"$certification_dry_run"
grep -Fq 'render-local-kubernetes-manifest.sh' <<<"$certification_dry_run"
if grep -Fq 'kind load docker-image' <<<"$certification_dry_run"; then
  printf '%s\n' 'Certification dry-run still imports images directly into kind.' >&2
  exit 1
fi

# Migrations, durable barrier, workloads, then connector registration remain ordered.
grep -Fq 'apply_kubernetes_migrations' "$kubernetes_lib" "$run_lib"
grep -Fq 'apply_kubernetes_topic_provisioning' "$kubernetes_lib" "$run_lib"
grep -Fq 'local-kubernetes-migrations.yaml' "$run_lib"
grep -Fq 'local-kubernetes-workloads.yaml' "$run_lib"
barrier_publish_line="$(grep -n 'run_logged kubernetes-open-barriers publish_local_matching_open_barriers' "$run_lib" | tail -1 | cut -d: -f1)"
workload_apply_line="$(grep -n 'run_logged kubernetes-workload-apply kubectl apply -f "$workload_manifest"' "$run_lib" | tail -1 | cut -d: -f1)"
connector_register_line="$(grep -n 'run_logged kubernetes-risk-outbox-connector register_kubernetes_risk_connector' "$run_lib" | tail -1 | cut -d: -f1)"
[[ -n "$barrier_publish_line" && -n "$workload_apply_line" && -n "$connector_register_line" && \
  "$barrier_publish_line" -lt "$workload_apply_line" && \
  "$workload_apply_line" -lt "$connector_register_line" ]] || {
  printf '%s\n' 'Certification must publish the durable Matching barrier before workloads and register Connect afterward.' >&2
  exit 1
}

grep -Fq -- "--for=jsonpath='{.status.readyReplicas}'=3 statefulset/kafka --timeout=300s" "$kubernetes_lib"
grep -Fq 'register_kubernetes_risk_connector' "$connect_lib" "$run_lib"
grep -Fq 'local-kubernetes-inputs.yaml' "$run_lib"
if grep -Fq 'prepare_kubernetes_bridge' "$runner" "$kubernetes_lib" "$run_lib"; then
  printf '%s\n' 'Certification still depends on the obsolete Compose-to-Kubernetes bridge.' >&2
  exit 1
fi

for required_topic in matching.commands matching.events account.lifecycle marketdata.events; do
  grep -Fq "$required_topic" "$kafka_lib" "$kubernetes_lib" || {
    printf 'Certification does not provision required topic %s.\n' "$required_topic" >&2
    exit 1
  }
done

grep -Fq 'publish_local_matching_open_barriers' "$kubernetes_lib" "$run_lib"
grep -Fq 'kubernetes-open-barriers' "$run_lib"
grep -Fq -- '--matching-fleet-only' "$framework_lib" "$bootstrap_lib"
grep -Fq 'select_matching_workload' "$workloads_lib" "$run_lib"
grep -Fq 'simplematch_local_image_lock_digest "$image_lock" matching' "$run_lib"
grep -Fq 'simplematch_local_image_lock_digest_reference "$image_lock" matching' "$run_lib"
grep -Fq 'trading_session_id=${trading_day}-regular' "$kubernetes_lib"
grep -Fq -- '--allow-shared-node' "$workloads_lib"

for removed_contract in '--image-transport' 'SIMPLEMATCH_LOCAL_IMAGE_TRANSPORT_DEFAULT' 'kind-load' \
  'normalize-local-images-for-kind.sh' '--allow-local-image'; do
  if grep -Fq -- "$removed_contract" "$runner" "$framework_lib" "$bootstrap_lib" "$run_lib" "$kubernetes_lib" "$workloads_lib"; then
    printf 'Removed image transport contract still appears in certification: %s\n' "$removed_contract" >&2
    exit 1
  fi
done

# Modularity is itself a contract: orchestration stays small and implementation lives by domain.
runner_lines="$(wc -l <"$runner")"
(( runner_lines < 150 )) || {
  printf 'Certification orchestrator grew past its intended boundary: %s lines.\n' "$runner_lines" >&2
  exit 1
}
grep -Fq 'wait_for_compose()' "$kafka_lib"
grep -Fq 'render_local_kubernetes_manifest()' "$kubernetes_lib"
grep -Fq 'register_kubernetes_risk_connector()' "$connect_lib"
grep -Fq 'verify_local_matching_fleet()' "$workloads_lib"
grep -Fq 'write_report()' "$framework_lib"

bash "$repo_root/scripts/validate-matching-producer-contract.sh"
"$repo_root/scripts/run-matching-kafka-failure-check.sh" --help >/dev/null

for service in account-service risk-service persistence market-data-projection marketdata-publisher marketdata-streamer; do
  grep -Fq 'implementation("org.springframework.boot:spring-boot-starter-web")' \
    "$repo_root/services/$service/build.gradle.kts" || {
    printf '%s does not provide the HTTP management runtime required by Kubernetes probes.\n' "$service" >&2
    exit 1
  }
done

ruby -r yaml -e 'YAML.load_file(ARGV.fetch(0), aliases: true)' \
  "$repo_root/deploy/compose/kafka-connect.production-like.yml" >/dev/null

printf '%s\n' 'Local production-like registry and certification contracts are valid.'
