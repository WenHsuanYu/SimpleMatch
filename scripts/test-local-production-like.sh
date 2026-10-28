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
phase_graph_lib="$script_dir/lib/local-certification-phase-graph.sh"
evidence_lib="$script_dir/lib/local-certification-evidence.sh"
images_lib="$script_dir/lib/local-certification-images.sh"
docker_storage_lib="$script_dir/lib/local-docker-storage.sh"
docker_storage_test="$script_dir/test-local-docker-storage-preflight.sh"
cdc_fixture_lib="$script_dir/lib/cdc-observer-fixture.sh"
cdc_fixture_test="$script_dir/test-cdc-observer-fixture-contract.sh"
focused_diagnostic_lib="$script_dir/lib/local-certification-focused-diagnostic.sh"
focused_diagnostic_script="$script_dir/run-local-cdc-delivery-focused-diagnostic.sh"
focused_diagnostic_test="$script_dir/test-local-certification-focused-diagnostic.sh"
job_supervision_test="$script_dir/test-local-certification-job-supervision.sh"
normalizer="$script_dir/normalize-local-images-for-kind.sh"
normalizer_dockerfile="$repo_root/deploy/docker/Dockerfile.kind-normalized"

trap 'printf "Local production-like contract failed at line %s: %s\n" "$LINENO" "$BASH_COMMAND" >&2' ERR

for file in \
  "$runner" "$framework_lib" "$kafka_lib" "$kubernetes_lib" \
  "$connect_lib" "$workloads_lib" "$bootstrap_lib" "$run_lib" \
  "$transport_lib" "$phase_graph_lib" \
  "$evidence_lib" "$images_lib" "$docker_storage_lib" \
  "$cdc_fixture_lib" \
  "$focused_diagnostic_lib" "$script_dir/lib/local-resilience-dependencies.sh"; do
  bash -n "$file"
done
bash -n "$cdc_fixture_test"
bash -n "$docker_storage_test"
bash -n "$focused_diagnostic_script" "$focused_diagnostic_test" "$job_supervision_test"
bash -n "$script_dir/run-local-resilience-dependencies.sh" \
  "$script_dir/test-local-resilience-dependencies.sh"

image_list="$($script_dir/build-local-images.sh --list)"
expected_images=(
  'simplematch/account-service:local'
  'simplematch/risk-service:local'
  'simplematch/persistence:local'
  'simplematch/market-data-projection:local'
  'simplematch/marketdata-streamer:local'
  'simplematch/query-service:local'
  'simplematch/flyway-runner:local'
  'simplematch/risk-matching-e2e-verifier:local'
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
bash -n "$repo_root/scripts/run-risk-cdc-delivery-observer-check.sh"
bash -n "$repo_root/scripts/lib/local-kind.sh"
observer_script="$repo_root/scripts/run-risk-cdc-delivery-observer-check.sh"
bash "$cdc_fixture_test"
bash "$docker_storage_test"
bash "$focused_diagnostic_test"
bash "$job_supervision_test"

# Image delivery remains dual-transport. Registry is the default immutable
# deployment path; kind-load is a fresh compatibility fallback.
bash -n "$script_dir/prepare-local-kubernetes-images.sh"
bash -n "$script_dir/publish-local-images.sh"
bash -n "$script_dir/render-local-kubernetes-manifest.sh"
bash -n "$normalizer"
[[ -f "$normalizer_dockerfile" ]] || {
  printf '%s\n' 'kind-load fallback normalization Dockerfile is missing.' >&2
  exit 1
}

"$runner" --help >/dev/null
grep -Fq 'SIMPLEMATCH_CERTIFICATION_TRADING_DAY' "$bootstrap_lib"
grep -Fq "jq '.immutable = true'" "$kubernetes_lib"
ruby - "$kubernetes_lib" <<'RUBY'
path = ARGV.fetch(0)
source = File.read(path, encoding: "UTF-8")
body = source[/^apply_local_kubernetes_inputs\(\).*?^\}/m]
abort "Kubernetes input helper is missing" unless body
abort "QuickFIX input must be rendered immutable before create" unless
  body.match?(/create -f "\$input_manifest"\s+--dry-run=client -o json.*?jq '\.immutable = true'.*?kubectl --context "\$kind_context" -n "\$namespace" create -f -/m)
abort "QuickFIX input must not use client-side apply" if
  body.match?(/create -f "\$input_manifest"\s+--dry-run=client -o json\s+\|\s+jq '\.immutable = true'\s+\|\s+kubectl .*? apply -f -/m)
RUBY
grep -Fq -- '--resume' "$bootstrap_lib"
grep -Fq -- '--image-transport' "$framework_lib" "$bootstrap_lib"
grep -Fq 'simplematch_local_image_transport_validate "$image_transport"' "$bootstrap_lib"
grep -Fq 'image_transport=%s' "$bootstrap_lib"
grep -Fq 'SIMPLEMATCH_CERTIFICATION_TIMEOUT_SECONDS' \
  "$runner" "$framework_lib" "$bootstrap_lib"
grep -Fq 'SIMPLEMATCH_KAFKA_CAPACITY_WORKLOAD_FILE' "$runner"
grep -Fq 'scripts/testdata/matching-topic-profile/local/capacity.properties' "$runner"
grep -Fq 'fixture_wait_timeout_seconds=600' "$kubernetes_lib"
grep -Fq -- '--timeout="${fixture_wait_timeout_seconds}s"' "$kubernetes_lib"
grep -Fq 'workload.commands.per.day=10000' \
  "$repo_root/scripts/testdata/matching-topic-profile/local/capacity.properties"
grep -Fq 'workload.events.per.day=10000' \
  "$repo_root/scripts/testdata/matching-topic-profile/local/capacity.properties"

grep -Fq 'market-reference-${trading_day//-/}' "$kubernetes_lib"
grep -Fq 'kubectl -n "$namespace" create -f "$artifact_manifest"' "$kubernetes_lib"
grep -Fq 'create_certification_namespace' "$kubernetes_lib"
grep -Fq 'Certification namespace already exists' "$kubernetes_lib"
grep -Fq 'simplematch_kind_delete_disposable_namespace' "$framework_lib"

# Default certification must expose each registry publication instead of the
# old all-at-once image-preparation command. Artifact rendering stays behind the
# Kubernetes manifest phase adapter and is tested independently below.
certification_dry_run="$($runner --dry-run --skip-build --skip-compose)"
grep -Fq 'test-kubernetes-overlays.sh' <<<"$certification_dry_run"
grep -Fq 'test-phase1-deployment-contracts.sh' <<<"$certification_dry_run"
grep -Fq 'ack-mode: manual_immediate' \
  "$repo_root/services/risk-service/src/main/resources/application.yaml"
grep -Fq 'test-local-kubernetes-dependencies.sh' <<<"$certification_dry_run"
grep -Fq 'test-matching-topic-profile.sh' <<<"$certification_dry_run"
grep -Fq 'publish-local-images.sh' <<<"$certification_dry_run"
grep -Fq -- '--service account-service' <<<"$certification_dry_run"
grep -Fq 'certification_construct_registry_image_lock' <<<"$certification_dry_run"
grep -Fq '_certification_render_and_split_kubernetes_manifest' <<<"$certification_dry_run"
grep -Fq '_certification_check_local_resource_budget' <<<"$certification_dry_run"
if grep -Fq 'kind load docker-image' <<<"$certification_dry_run"; then
  printf '%s\n' 'Default certification dry-run unexpectedly imports images directly into kind.' >&2
  exit 1
fi

# Explicit fallback remains behind the existing preparation adapter.
fallback_dry_run="$($runner --image-transport kind-load --matching-fleet-only \
  --dry-run --skip-build --skip-compose)"
grep -Fq 'prepare-local-kubernetes-images.sh' <<<"$fallback_dry_run"
grep -Fq -- '--transport kind-load' <<<"$fallback_dry_run"
grep -Fq -- '--matching-only' <<<"$fallback_dry_run"
if grep -Fq 'kind load docker-image' <<<"$fallback_dry_run"; then
  printf '%s\n' 'Top-level certification dry-run leaks direct kind-load implementation details.' >&2
  exit 1
fi

# Check the actual dry-run adapter sequence instead of repeating graph declarations.
phase_order="$(awk '$1 == "PHASE" {print $2}' <<<"$certification_dry_run")"
phase_index() {
  awk -v target="$1" '$0 == target { print NR; exit }' <<<"$phase_order"
}
assert_phase_precedes() {
  local first second
  first="$(phase_index "$1")"
  second="$(phase_index "$2")"
  [[ -n "$first" && -n "$second" && "$first" -lt "$second" ]] || {
    printf 'Certification phase order is invalid: %s must precede %s.\n' "$1" "$2" >&2
    exit 1
  }
}
assert_phase_precedes kubernetes-topic-provisioning kubernetes-connect-apply
assert_phase_precedes kubernetes-connect-apply kubernetes-open-barriers
assert_phase_precedes kubernetes-open-barriers kubernetes-workload-apply
assert_phase_precedes kubernetes-workload-apply kubernetes-workloads

bash "$repo_root/scripts/validate-matching-producer-contract.sh"
"$repo_root/scripts/run-matching-kafka-failure-check.sh" --help >/dev/null

ruby -r yaml -e 'YAML.load_file(ARGV.fetch(0), aliases: true)' \
  "$repo_root/deploy/compose/kafka-connect.production-like.yml" >/dev/null

bash "$repo_root/scripts/test-local-resource-budget-evidence.sh"

printf '%s\n' \
  'Local production-like image and certification contracts are valid.'
