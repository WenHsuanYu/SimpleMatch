#!/usr/bin/env bash
set -Eeuo pipefail
IFS=$'\n\t'

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd -- "$script_dir/.." && pwd)"
real_repo_root="$repo_root"

# shellcheck source=scripts/lib/local-certification-phase-graph.sh
source "$script_dir/lib/local-certification-phase-graph.sh"
# shellcheck source=scripts/lib/local-certification-fingerprint.sh
source "$script_dir/lib/local-certification-fingerprint.sh"
# shellcheck source=scripts/lib/local-certification-evidence.sh
source "$script_dir/lib/local-certification-evidence.sh"
# shellcheck source=scripts/lib/local-certification-planner.sh
source "$script_dir/lib/local-certification-planner.sh"
# shellcheck source=scripts/lib/local-certification-images.sh
source "$script_dir/lib/local-certification-images.sh"
# shellcheck source=scripts/lib/local-certification-kafka.sh
source "$script_dir/lib/local-certification-kafka.sh"
# shellcheck source=scripts/lib/local-certification-artifacts.sh
source "$script_dir/lib/local-certification-artifacts.sh"

fail() {
  printf 'certification review hardening contract failed: %s\n' "$*" >&2
  exit 1
}

assert_eq() {
  local expected="$1"
  local actual="$2"
  local message="$3"
  [[ "$actual" == "$expected" ]] || \
    fail "$message: expected=$expected actual=$actual"
}

assert_contains() {
  local haystack="$1"
  local needle="$2"
  local message="$3"
  [[ "$haystack" == *"$needle"* ]] || fail "$message: missing=$needle"
}

# Phase definitions are the single declarative source for policy-relevant
# metadata. These are public properties, not traversal implementation details.
[[ -n "$(certification_phase_input_kinds local-image-build/quickfix-gateway)" ]] || \
  fail 'image build phase does not declare input kinds'
assert_eq docker-image \
  "$(certification_phase_output_kinds local-image-build/quickfix-gateway)" \
  'image build phase does not declare its output kind'
assert_eq REEXECUTE "$(certification_phase_resume_mode compose-wait)" \
  'ordinary fresh runtime phase must re-execute on resume'
assert_eq VALIDATE "$(certification_phase_resume_mode kubernetes-namespace)" \
  'namespace resume requires current-state validation'
assert_eq FORBID "$(certification_phase_resume_mode kubernetes-open-barriers)" \
  'Open Barrier resume must fail closed without a safe validator'

# Required phase order must come from DAG edges, not phase declaration order.
skip_build=false
skip_compose=false
skip_kubernetes=false
matching_fleet_only=false
image_transport=registry
unset SIMPLEMATCH_KAFKA_PRODUCER_CONFIG_FILE || true
certification_phase_validate_graph || fail 'phase graph is invalid before ordering test'
mapfile -t original_registration_order < <(certification_phase_ids)
reversed=()
for ((index=${#original_registration_order[@]} - 1; index >= 0; index--)); do
  reversed+=("${original_registration_order[$index]}")
done
SIMPLEMATCH_CERTIFICATION_PHASE_ORDER=("${reversed[@]}")
mapfile -t ordered_required < <(certification_required_phase_ids)
declare -A position=()
for index in "${!ordered_required[@]}"; do
  position["${ordered_required[$index]}"]="$index"
done
for phase in "${ordered_required[@]}"; do
  dependency_output="$(certification_phase_dependencies "$phase")" || \
    fail "could not read dependencies for $phase"
  while IFS= read -r dependency; do
    [[ -n "$dependency" ]] || continue
    [[ -n "${position[$dependency]+x}" ]] || \
      fail "$phase dependency $dependency is absent from required order"
    (( position[$dependency] < position[$phase] )) || \
      fail "$phase appeared before dependency $dependency"
  done <<<"$dependency_output"
done
SIMPLEMATCH_CERTIFICATION_PHASE_ORDER=("${original_registration_order[@]}")

# Planner traversal consumes the graph-owned order and invokes one dispatcher.
executed_phases=()
record_phase() {
  executed_phases+=("$1")
  # A real dispatcher can invoke a command that consumes inherited stdin. The
  # planner must keep traversal independent from that command input.
  if [[ "$1" == source-preflight ]]; then
    while IFS= read -r _; do :; done || true
  fi
}
certification_plan_execute record_phase || fail 'planner traversal failed'
assert_eq "$(printf '%s\n' "${ordered_required[@]}" | sort)" \
  "$(printf '%s\n' "${executed_phases[@]}" | sort)" \
  'planner traversal did not dispatch every required phase exactly once'

# Image identity must follow the effective BootBuildImage pull policy. ALWAYS
# must observe the registry even when a stale local image with the same tag
# exists; IF_NOT_PRESENT intentionally prefers the local image.
docker() {
  if [[ "$1 $2" == 'image inspect' ]]; then
    printf '%s\n' 'sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'
    return 0
  fi
  if [[ "$1 $2" == 'buildx version' ]]; then
    return 0
  fi
  if [[ "$1 $2 $3" == 'buildx imagetools inspect' ]]; then
    printf '%s\n' 'Name: example.invalid/builder:latest'
    printf '%s\n' 'Digest: sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb'
    return 0
  fi
  return 1
}
assert_eq \
  'sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb' \
  "$(certification_resolve_image_identity example.invalid/builder:latest ALWAYS)" \
  'ALWAYS pull policy used stale local builder identity'
assert_eq \
  'sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa' \
  "$(certification_resolve_image_identity example.invalid/builder:latest IF_NOT_PRESENT)" \
  'IF_NOT_PRESENT did not preserve local image identity'

# Registry publication fingerprint must include the implementation that owns
# publication semantics, not only the source image and registry endpoint.
fixture_root="$(mktemp -d)"
trap 'rm -rf "$fixture_root"' EXIT
mkdir -p "$fixture_root/scripts/lib"
for path in \
  scripts/publish-local-images.sh \
  scripts/lib/local-registry.sh \
  scripts/lib/local-image-inventory.sh \
  scripts/lib/local-image-transport.sh \
  scripts/lib/local-certification-images.sh \
  scripts/lib/local-certification-fingerprint.sh; do
  mkdir -p "$fixture_root/$(dirname -- "$path")"
  printf '%s\n' "fixture:$path:v1" >"$fixture_root/$path"
done
git -C "$fixture_root" init -q
git -C "$fixture_root" add .
repo_root="$fixture_root"
image_tag=local
certification_source_image_identity() {
  printf '%s\n' 'sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc'
}
simplematch_registry_endpoint() {
  printf '%s\n' 'localhost:5001'
}
simplematch_local_image_inventory_source_image() {
  printf 'simplematch-%s:local\n' "$1"
}
publish_before="$(certification_phase_fingerprint registry-publish/quickfix-gateway)" || \
  fail 'registry publication fingerprint could not be calculated'
printf '%s\n' 'fixture:scripts/publish-local-images.sh:v2' \
  >"$fixture_root/scripts/publish-local-images.sh"
publish_after="$(certification_phase_fingerprint registry-publish/quickfix-gateway)" || \
  fail 'registry publication fingerprint could not be recalculated'
[[ "$publish_before" != "$publish_after" ]] || \
  fail 'publication implementation change did not invalidate evidence'
repo_root="$real_repo_root"

# Evidence lookup returns a diagnostic MISS instead of forcing the planner to
# collapse every rejection into one generic reason.
cache_dir="$fixture_root/cache"
export SIMPLEMATCH_CERTIFICATION_CACHE_DIR="$cache_dir"
input_fingerprint="sha256:$(printf 'review-hardening' | sha256sum | awk '{print $1}')"
probe_json="$(certification_evidence_probe static-kubernetes-overlays "$input_fingerprint")" || \
  fail 'evidence probe failed on a normal cache miss'
assert_eq MISS "$(jq -r '.status' <<<"$probe_json")" \
  'missing cache index was not reported as MISS'
assert_contains "$(jq -r '.reason' <<<"$probe_json")" 'index' \
  'cache miss did not explain the missing index'

# Planner entries expose cache lookup/revalidation cost separately from command
# execution duration and accept one structured execution metadata object.
dry_run=false
evidence_dir="$fixture_root/run"
source_signature=review-hardening
run_id=review-hardening
certification_trading_day=2026-08-28
certification_required_phase_ids() {
  printf '%s\n' source-preflight static-kubernetes-overlays
}
certification_plan_initialize "$evidence_dir" || fail 'planner initialization failed'
source_plan="$(certification_plan_phase source-preflight)" || fail 'fresh plan failed'
IFS='|' read -r _ source_input _ source_reason <<<"$source_plan"
now='2026-08-28T00:00:00.000Z'
execution_json="$(certification_execution_timing_json "$now" "$now" 0)" || \
  fail 'structured execution metadata could not be created'
certification_plan_record_execution \
  source-preflight "$source_input" "$source_reason" "$execution_json" || \
  fail 'fresh phase result could not be recorded'
certification_plan_phase static-kubernetes-overlays >/dev/null || \
  fail 'content-addressed phase could not be planned'
jq -e '
  .phases[] |
  select(.phaseId == "static-kubernetes-overlays") |
  (.lookupDurationMillis | type == "number") and
  (.revalidationDurationMillis | type == "number")
' "$certification_plan_file" >/dev/null || \
  fail 'planner did not record lookup and revalidation timing'

# Reusable producer configuration is a real artifact, not an empty-output proof.
# It must be stored by content, restored into the current evidence directory,
# and rejected by same-run resume when the current file disappears.
matching_producer_config_file="$fixture_root/matching-producer.config.txt"
printf '%s\n' 'acks=all' 'enable.idempotence=true' >"$matching_producer_config_file"
producer_outputs="$(certification_phase_outputs_json kafka-producer-contract)" || \
  fail 'producer configuration output could not be described'
jq -e '
  length == 1 and
  .[0].kind == "file-content" and
  .[0].name == "matching-producer-config" and
  (.[0].identity | test("^sha256:[0-9a-f]{64}$")) and
  (.[0].contentBase64 | type == "string" and length > 0)
' <<<"$producer_outputs" >/dev/null || \
  fail 'producer configuration output is not content addressed'
producer_input="sha256:$(printf producer-config | sha256sum | awk '{print $1}')"
producer_result="$fixture_root/producer-result.json"
jq -n \
  --arg input "$producer_input" \
  --argjson outputs "$producer_outputs" '{
    schemaVersion: 1,
    phaseId: "kafka-producer-contract",
    definitionVersion: 1,
    decision: "EXECUTED",
    status: "PASS",
    inputFingerprint: $input,
    evidenceDigest: null,
    reason: "test",
    execution: {
      sourceRevision: "test",
      startedAtUtc: "2026-08-28T00:00:00Z",
      completedAtUtc: "2026-08-28T00:00:01Z",
      durationMillis: 1000
    },
    outputs: $outputs
  }' >"$producer_result"
producer_evidence="$(certification_evidence_publish \
  kafka-producer-contract "$producer_input" "$producer_result")" || \
  fail 'producer configuration evidence could not be published'
certification_phase_current_outputs_valid \
  kafka-producer-contract "$producer_result" || \
  fail 'current producer configuration output was not accepted'
rm -f -- "$matching_producer_config_file"
if certification_phase_current_outputs_valid \
    kafka-producer-contract "$producer_result"; then
  fail 'same-run output validation accepted a missing producer configuration'
fi
certification_phase_materialize_reused_outputs \
  kafka-producer-contract "$producer_evidence" || \
  fail 'producer configuration could not be materialized from reusable evidence'
grep -Fxq 'acks=all' "$matching_producer_config_file" || \
  fail 'materialized producer configuration content is incomplete'
certification_phase_current_outputs_valid \
  kafka-producer-contract "$producer_result" || \
  fail 'materialized producer configuration did not validate'

printf '%s\n' 'Local certification review hardening contracts are valid.'
