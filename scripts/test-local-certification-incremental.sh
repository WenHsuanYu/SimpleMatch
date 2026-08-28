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

fail() {
  printf 'incremental certification contract failed: %s\n' "$*" >&2
  exit 1
}

assert_eq() {
  local expected="$1"
  local actual="$2"
  local message="$3"
  [[ "$actual" == "$expected" ]] || \
    fail "$message: expected=$expected actual=$actual"
}

assert_has_line() {
  local haystack="$1"
  local expected="$2"
  local message="$3"
  grep -Fxq -- "$expected" <<<"$haystack" || fail "$message: $expected"
}

assert_lacks_line() {
  local haystack="$1"
  local unexpected="$2"
  local message="$3"
  if grep -Fxq -- "$unexpected" <<<"$haystack"; then
    fail "$message: $unexpected"
  fi
}

for module in \
  local-certification-phase-graph.sh \
  local-certification-fingerprint.sh \
  local-certification-evidence.sh \
  local-certification-planner.sh \
  local-certification-images.sh; do
  bash -n "$script_dir/lib/$module"
done

skip_build=false
skip_compose=false
skip_kubernetes=false
image_transport=registry
matching_fleet_only=false
unset SIMPLEMATCH_KAFKA_PRODUCER_CONFIG_FILE || true
certification_phase_validate_graph || fail 'registry Phase DAG is invalid'
image_transport=kind-load
certification_phase_validate_graph || fail 'kind-load Phase DAG is invalid'
image_transport=registry

for phase in \
  source-preflight \
  kafka-capacity-evidence \
  kafka-broker-failure-live \
  kind-load-import \
  kubernetes-namespace \
  kubernetes-inputs \
  kubernetes-platform-apply \
  kubernetes-migrations \
  kubernetes-topic-provisioning \
  kubernetes-open-barriers \
  kubernetes-workload-apply \
  kubernetes-workloads \
  kubernetes-fleet \
  retained-run-provenance; do
  assert_eq FRESH "$(certification_phase_policy "$phase")" \
    "$phase must remain fresh"
done
assert_eq CONTENT_ADDRESSED \
  "$(certification_phase_policy static-kubernetes-overlays)" \
  'static Kubernetes validation must be content addressed'
assert_eq CONTENT_ADDRESSED \
  "$(certification_phase_policy local-image-build/quickfix-gateway)" \
  'QuickFIX image build must be content addressed'
assert_eq REVALIDATE \
  "$(certification_phase_policy registry-publish/quickfix-gateway)" \
  'registry publication must be revalidated'

full_required="$(certification_required_phase_ids)"
assert_has_line "$full_required" local-image-build/quickfix-gateway \
  'full profile omitted QuickFIX image build'
assert_has_line "$full_required" registry-publish/quickfix-gateway \
  'full registry profile omitted QuickFIX publication'
assert_has_line "$full_required" kubernetes-migrations \
  'full profile omitted Kubernetes migrations'
assert_has_line "$full_required" retained-run-provenance \
  'full profile omitted retained provenance'

matching_fleet_only=true
matching_required="$(certification_required_phase_ids)"
assert_has_line "$matching_required" local-image-build/matching \
  'Matching profile omitted Matching image build'
assert_lacks_line "$matching_required" local-image-build/quickfix-gateway \
  'Matching profile selected unrelated QuickFIX image build'
assert_has_line "$matching_required" kubernetes-topic-provisioning \
  'Matching profile omitted topic provisioning'
assert_lacks_line "$matching_required" retained-run-provenance \
  'partial Matching profile selected retained full-run provenance'
matching_fleet_only=false

fixture_root="$(mktemp -d)"
trap 'rm -rf "$fixture_root"' EXIT

create_fingerprint_fixture() {
  local destination="$1"
  mkdir -p \
    "$destination/services/quickfix-gateway" \
    "$destination/services/account-service" \
    "$destination/shared-java" \
    "$destination/proto" \
    "$destination/build-logic" \
    "$destination/gradle" \
    "$destination/scripts/lib"
  printf '%s\n' 'quickfix-v1' \
    >"$destination/services/quickfix-gateway/source.txt"
  printf '%s\n' 'account-v1' \
    >"$destination/services/account-service/source.txt"
  printf '%s\n' 'shared-v1' >"$destination/shared-java/shared.txt"
  printf '%s\n' 'proto-v1' >"$destination/proto/contracts.proto"
  printf '%s\n' 'build-logic-v1' >"$destination/build-logic/plugin.txt"
  printf '%s\n' 'versions-v1' >"$destination/gradle/libs.versions.toml"
  printf '%s\n' 'settings-v1' >"$destination/settings.gradle.kts"
  printf '%s\n' 'root-build-v1' >"$destination/build.gradle.kts"
  printf '%s\n' '#!/usr/bin/env sh' >"$destination/gradlew"
  chmod 0755 "$destination/gradlew"
  printf '%s\n' '@echo off' >"$destination/gradlew.bat"
  printf '%s\n' 'build-images-v1' >"$destination/scripts/build-local-images.sh"
  printf '%s\n' 'inventory-v1' \
    >"$destination/scripts/lib/local-image-inventory.sh"
  git -C "$destination" init -q
  git -C "$destination" add .
}

fixture_a="$fixture_root/a"
fixture_b="$fixture_root/b"
create_fingerprint_fixture "$fixture_a"
cp -a "$fixture_a" "$fixture_b"
rm -rf "$fixture_b/.git"
git -C "$fixture_b" init -q
git -C "$fixture_b" add .

# Remote builder/run-image resolution is an external dependency. The
# fingerprint seam receives a deterministic adapter value in this contract.
_certification_spring_toolchain_identity() {
  printf '%s\n' \
    'builder=paketobuildpacks/builder-noble-java-tiny@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa' \
    'runImage=paketobuildpacks/ubuntu-noble-run-tiny@sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb' \
    'pullPolicy=DEFAULT'
}

repo_root="$fixture_a"
_certification_spring_common_fingerprint=""
quickfix_a="$(certification_image_input_fingerprint quickfix-gateway)"
account_a="$(certification_image_input_fingerprint account-service)"

repo_root="$fixture_b"
_certification_spring_common_fingerprint=""
quickfix_b="$(certification_image_input_fingerprint quickfix-gateway)"
account_b="$(certification_image_input_fingerprint account-service)"
assert_eq "$quickfix_a" "$quickfix_b" \
  'absolute workspace path must not affect QuickFIX fingerprint'
assert_eq "$account_a" "$account_b" \
  'absolute workspace path must not affect account fingerprint'

touch -d '2030-01-01 00:00:00 UTC' \
  "$fixture_b/services/quickfix-gateway/source.txt"
_certification_spring_common_fingerprint=""
assert_eq "$quickfix_b" \
  "$(certification_image_input_fingerprint quickfix-gateway)" \
  'mtime must not affect image fingerprint'

printf '%s\n' 'quickfix-v2' \
  >"$fixture_b/services/quickfix-gateway/source.txt"
_certification_spring_common_fingerprint=""
quickfix_changed="$(certification_image_input_fingerprint quickfix-gateway)"
account_after_quickfix="$(certification_image_input_fingerprint account-service)"
[[ "$quickfix_changed" != "$quickfix_b" ]] || \
  fail 'QuickFIX source change did not invalidate QuickFIX image'
assert_eq "$account_b" "$account_after_quickfix" \
  'QuickFIX-only source change invalidated unrelated account image'

printf '%s\n' 'shared-v2' >"$fixture_b/shared-java/shared.txt"
_certification_spring_common_fingerprint=""
quickfix_after_shared="$(certification_image_input_fingerprint quickfix-gateway)"
account_after_shared="$(certification_image_input_fingerprint account-service)"
[[ "$quickfix_after_shared" != "$quickfix_changed" ]] || \
  fail 'shared input did not invalidate QuickFIX image'
[[ "$account_after_shared" != "$account_after_quickfix" ]] || \
  fail 'shared input did not invalidate account image'

certification_trading_day=2026-08-27
_certification_spring_common_fingerprint=""
account_day_one="$(certification_image_input_fingerprint account-service)"
certification_trading_day=2026-08-28
_certification_spring_common_fingerprint=""
account_day_two="$(certification_image_input_fingerprint account-service)"
assert_eq "$account_day_one" "$account_day_two" \
  'trading day must not invalidate application image build'

repo_root="$real_repo_root"
cache_dir="$fixture_root/cache"
export SIMPLEMATCH_CERTIFICATION_CACHE_DIR="$cache_dir"
input_fingerprint="sha256:$(printf 'static-input' | sha256sum | awk '{print $1}')"
result_file="$fixture_root/static-result.json"
jq -n --arg input "$input_fingerprint" '{
  schemaVersion: 1,
  phaseId: "static-kubernetes-overlays",
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
  outputs: []
}' >"$result_file"

evidence_digest="$(certification_evidence_publish \
  static-kubernetes-overlays "$input_fingerprint" "$result_file")" || \
  fail 'valid evidence could not be published'
assert_eq "$evidence_digest" \
  "$(certification_evidence_find_valid \
    static-kubernetes-overlays "$input_fingerprint")" \
  'published evidence could not be found'
materialized="$fixture_root/materialized.json"
certification_evidence_materialize "$evidence_digest" "$materialized" || \
  fail 'valid evidence could not be materialized'
cmp -s "$result_file" "$materialized" || \
  fail 'materialized evidence differs from immutable object'

SIMPLEMATCH_CERTIFICATION_PHASE_VERSION[static-kubernetes-overlays]=2
if certification_evidence_find_valid \
    static-kubernetes-overlays "$input_fingerprint" >/dev/null 2>&1; then
  fail 'evidence from an older phase definition was accepted'
fi
SIMPLEMATCH_CERTIFICATION_PHASE_VERSION[static-kubernetes-overlays]=1

object_path="$(_certification_evidence_object_path "$evidence_digest")"
printf '%s\n' '{"corrupt":true}' >"$object_path"
if certification_evidence_find_valid \
    static-kubernetes-overlays "$input_fingerprint" >/dev/null 2>&1; then
  fail 'corrupt evidence object was accepted'
fi

failed_result="$fixture_root/failed-result.json"
jq '.status = "FAIL"' "$result_file" >"$failed_result"
if certification_evidence_publish \
    static-kubernetes-overlays "$input_fingerprint" "$failed_result" \
    >/dev/null 2>&1; then
  fail 'non-PASS evidence entered reusable store'
fi

# Registry evidence is reusable only while the exact immutable digest remains
# addressable. The check is injected here so the contract does not require a
# running registry.
export SIMPLEMATCH_CERTIFICATION_CACHE_DIR="$fixture_root/registry-cache"
registry_input="sha256:$(printf 'registry-input' | sha256sum | awk '{print $1}')"
registry_digest="sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
registry_location="localhost:5001/quickfix-gateway@$registry_digest"
registry_entry="quickfix-gateway|quickfix-gateway:local|localhost:5001/quickfix-gateway:local|$registry_location"
registry_result="$fixture_root/registry-result.json"
jq -n \
  --arg input "$registry_input" \
  --arg identity "$registry_digest" \
  --arg location "$registry_location" \
  --arg entry "$registry_entry" '{
    schemaVersion: 1,
    phaseId: "registry-publish/quickfix-gateway",
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
    outputs: [{
      kind: "registry-image",
      name: "quickfix-gateway",
      identity: $identity,
      location: $location,
      entry: $entry
    }]
  }' >"$registry_result"
registry_evidence="$(certification_evidence_publish \
  registry-publish/quickfix-gateway "$registry_input" "$registry_result")" || \
  fail 'registry evidence could not be published'
certification_registry_digest_available() {
  [[ "$1" == "$registry_location" ]]
}
certification_phase_revalidate \
  registry-publish/quickfix-gateway "$registry_evidence" || \
  fail 'addressable registry digest did not revalidate'
certification_registry_digest_available() {
  return 1
}
if certification_phase_revalidate \
    registry-publish/quickfix-gateway "$registry_evidence"; then
  fail 'missing registry digest was accepted during revalidation'
fi

# Complete image-lock construction consumes one canonical fragment per service
# and must remain independent of registry availability.
registry_fragment_directory="$fixture_root/fragments"
image_lock="$fixture_root/local-images.lock"
image_tag=local
matching_fleet_only=false
mkdir -p "$registry_fragment_directory"
while IFS='|' read -r _ service _ repository; do
  fake_digest="sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"
  printf '%s|%s:%s|localhost:5001/%s:%s|localhost:5001/%s@%s\n' \
    "$service" "$repository" "$image_tag" "$repository" "$image_tag" \
    "$repository" "$fake_digest" \
    >"$registry_fragment_directory/${service}.lock"
done < <(simplematch_local_image_inventory_entries)
certification_construct_registry_image_lock || \
  fail 'complete registry image lock could not be reconstructed'
assert_eq full "$(simplematch_local_image_lock_render_profile "$image_lock")" \
  'reconstructed image lock has the wrong deployment profile'

# Planner tests use a deliberately small graph profile so each scenario tests
# planner behavior rather than repeating the complete production-like runner.
certification_required_phase_ids() {
  printf '%s\n' source-preflight static-kubernetes-overlays
}
export SIMPLEMATCH_CERTIFICATION_CACHE_DIR="$fixture_root/planner-cache"
dry_run=false
image_transport=registry
matching_fleet_only=false
skip_build=false
skip_compose=false
skip_kubernetes=false
certification_trading_day=2026-08-28
source_signature=planner-source
run_id=planner-one
evidence_dir="$fixture_root/run-one"
certification_plan_initialize "$evidence_dir" || \
  fail 'planner could not initialize first run'

source_plan="$(certification_plan_phase source-preflight)" || \
  fail 'fresh phase could not be planned'
IFS='|' read -r source_decision source_input _ source_reason <<<"$source_plan"
assert_eq EXECUTE "$source_decision" 'fresh phase did not execute'
certification_plan_record_execution \
  source-preflight "$source_input" "$source_reason" \
  2026-08-28T00:00:00Z 2026-08-28T00:00:00Z 0 || \
  fail 'fresh phase result could not be recorded'

static_plan="$(certification_plan_phase static-kubernetes-overlays)" || \
  fail 'static phase could not be planned'
IFS='|' read -r static_decision static_input _ static_reason <<<"$static_plan"
assert_eq EXECUTE "$static_decision" \
  'cold static phase unexpectedly reused evidence'
certification_plan_record_execution \
  static-kubernetes-overlays "$static_input" "$static_reason" \
  2026-08-28T00:00:00Z 2026-08-28T00:00:01Z 1000 || \
  fail 'static phase execution evidence could not be recorded'
certification_plan_finalize || fail 'first plan did not finalize'

run_id=planner-two
evidence_dir="$fixture_root/run-two"
certification_plan_initialize "$evidence_dir" || \
  fail 'planner could not initialize second run'
source_plan="$(certification_plan_phase source-preflight)" || \
  fail 'second fresh phase could not be planned'
IFS='|' read -r source_decision source_input _ source_reason <<<"$source_plan"
assert_eq EXECUTE "$source_decision" 'fresh phase reused cross-run evidence'
certification_plan_record_execution \
  source-preflight "$source_input" "$source_reason" \
  2026-08-28T00:00:00Z 2026-08-28T00:00:00Z 0 || \
  fail 'second fresh phase result could not be recorded'

static_plan="$(certification_plan_phase static-kubernetes-overlays)" || \
  fail 'warm static phase could not be planned'
IFS='|' read -r static_decision static_input static_evidence static_reason \
  <<<"$static_plan"
assert_eq REUSE "$static_decision" \
  'warm static phase did not reuse exact evidence'
[[ "$static_evidence" =~ ^sha256:[0-9a-f]{64}$ ]] || \
  fail 'reuse decision omitted evidence identity'
certification_plan_record_reuse \
  static-kubernetes-overlays "$static_decision" "$static_input" \
  "$static_evidence" "$static_reason" \
  2026-08-28T00:00:00Z 2026-08-28T00:00:00Z 0 || \
  fail 'reused phase evidence could not be materialized'
certification_plan_finalize || fail 'second plan did not finalize'

jq -e '
  [.phases[] | select(.phaseId == "static-kubernetes-overlays")][0].decision == "REUSE"
' "$evidence_dir/plan.json" >/dev/null || \
  fail 'plan.json did not record REUSE decision'
jq -e '
  .decision == "REUSED" and
  .status == "PASS" and
  .execution.durationMillis == 0
' "$evidence_dir/phases/static-kubernetes-overlays/result.json" >/dev/null || \
  fail 'reused current-run result is incomplete'

run_id=planner-incomplete
evidence_dir="$fixture_root/run-incomplete"
certification_plan_initialize "$evidence_dir" || \
  fail 'planner could not initialize incomplete run'
source_plan="$(certification_plan_phase source-preflight)" || \
  fail 'incomplete run could not plan source preflight'
IFS='|' read -r source_decision source_input _ source_reason <<<"$source_plan"
certification_plan_record_execution \
  source-preflight "$source_input" "$source_reason" \
  2026-08-28T00:00:00Z 2026-08-28T00:00:00Z 0 || \
  fail 'incomplete run could not record source preflight'
if certification_plan_finalize >/dev/null 2>&1; then
  fail 'plan finalization accepted a missing required phase'
fi

printf '%s\n' 'Incremental local certification contracts are valid.'
