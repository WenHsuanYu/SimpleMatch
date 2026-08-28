#!/usr/bin/env bash
set -Eeuo pipefail
IFS=$'\n\t'

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd -- "$script_dir/.." && pwd)"
bootstrap_lib="$script_dir/lib/local-certification-bootstrap.sh"

# shellcheck source=scripts/lib/local-certification-phase-graph.sh
source "$script_dir/lib/local-certification-phase-graph.sh"
# shellcheck source=scripts/lib/local-certification-fingerprint.sh
source "$script_dir/lib/local-certification-fingerprint.sh"
# shellcheck source=scripts/lib/local-certification-evidence.sh
source "$script_dir/lib/local-certification-evidence.sh"
# shellcheck source=scripts/lib/local-certification-planner.sh
source "$script_dir/lib/local-certification-planner.sh"

fail() {
  printf 'certification skip contract failed: %s\n' "$*" >&2
  exit 1
}

assert_plan_skip() {
  local plan_file="$1"
  local phase_id="$2"
  local expected_reason="$3"

  jq -e \
    --arg phase "$phase_id" \
    --arg reason "$expected_reason" '
      [.phases[] | select(.phaseId == $phase)] as $matches |
      ($matches | length) == 1 and
      $matches[0].decision == "SKIP" and
      $matches[0].inputFingerprint == null and
      $matches[0].evidenceDigest == null and
      $matches[0].reason == $reason
    ' "$plan_file" >/dev/null || \
    fail "plan does not record explicit SKIP for $phase_id"
}

assert_all_omissions_planned() {
  local plan_file="$1"
  local active_output="$2"
  local full_output="$3"
  local phase_id
  local -A active=()

  while IFS= read -r phase_id; do
    [[ -n "$phase_id" ]] && active["$phase_id"]=true
  done <<<"$active_output"
  while IFS= read -r phase_id; do
    [[ -n "$phase_id" ]] || continue
    [[ -n "${active[$phase_id]+x}" ]] && continue
    jq -e --arg phase "$phase_id" '
      [.phases[] | select(.phaseId == $phase and .decision == "SKIP")] |
      length == 1
    ' "$plan_file" >/dev/null || \
      fail "omitted full-profile phase has no SKIP entry: $phase_id"
  done <<<"$full_output"
}

fixture_root="$(mktemp -d)"
trap 'rm -rf "$fixture_root"' EXIT
export SIMPLEMATCH_CERTIFICATION_CACHE_DIR="$fixture_root/cache"
dry_run=false
resume=false
certification_trading_day=2026-08-28
source_signature=skip-contract
run_id=skip-contract
image_transport=registry
unset SIMPLEMATCH_KAFKA_PRODUCER_CONFIG_FILE || true

# Every explicit skip flag must be distinguishable from verified reuse.
skip_build=true
skip_compose=true
skip_kubernetes=true
matching_fleet_only=false
active_required="$(certification_required_phase_ids)" || \
  fail 'active explicit-skip profile could not be resolved'
full_required="$(
  (
    skip_build=false
    skip_compose=false
    skip_kubernetes=false
    matching_fleet_only=false
    certification_required_phase_ids
  )
)" || fail 'full comparison profile could not be resolved'
evidence_dir="$fixture_root/all-skipped"
certification_plan_initialize "$evidence_dir" || \
  fail 'planner could not initialize explicit-skip profile'
assert_plan_skip "$evidence_dir/plan.json" \
  local-image-build/quickfix-gateway 'operator set --skip-build'
assert_plan_skip "$evidence_dir/plan.json" \
  compose-up 'operator set --skip-compose'
assert_plan_skip "$evidence_dir/plan.json" \
  registry-publish/quickfix-gateway 'operator set --skip-kubernetes'
assert_plan_skip "$evidence_dir/plan.json" \
  kubernetes-namespace 'operator set --skip-kubernetes'
assert_all_omissions_planned \
  "$evidence_dir/plan.json" "$active_required" "$full_required"
[[ ! -e "$evidence_dir/phases/local-image-build/quickfix-gateway/result.json" ]] || \
  fail 'explicit SKIP created phase PASS evidence'

# Matching-only is an explicit partial profile, not cache reuse. It records work
# omitted relative to the full profile while keeping Matching phases selected.
skip_build=false
skip_compose=false
skip_kubernetes=false
matching_fleet_only=true
active_required="$(certification_required_phase_ids)" || \
  fail 'Matching-only profile could not be resolved'
full_required="$(
  (
    matching_fleet_only=false
    certification_required_phase_ids
  )
)" || fail 'full comparison profile could not be resolved'
evidence_dir="$fixture_root/matching-only"
certification_plan_initialize "$evidence_dir" || \
  fail 'planner could not initialize Matching-only profile'
assert_plan_skip "$evidence_dir/plan.json" \
  local-image-build/quickfix-gateway 'operator selected --matching-fleet-only'
assert_plan_skip "$evidence_dir/plan.json" \
  registry-publish/quickfix-gateway 'operator selected --matching-fleet-only'
assert_plan_skip "$evidence_dir/plan.json" \
  kubernetes-migrations 'operator selected --matching-fleet-only'
assert_plan_skip "$evidence_dir/plan.json" \
  retained-run-provenance 'operator selected --matching-fleet-only'
assert_all_omissions_planned \
  "$evidence_dir/plan.json" "$active_required" "$full_required"
if jq -e '
  any(.phases[]; .phaseId == "local-image-build/matching" and .decision == "SKIP")
' "$evidence_dir/plan.json" >/dev/null; then
  fail 'Matching-only profile skipped its required Matching image build'
fi

# Same-run resume must bind to the proof profile, not only namespace, cluster,
# trading day, image tag, transport, and source revision.
for profile_key in \
  skip_build \
  skip_compose \
  skip_kubernetes \
  matching_fleet_only; do
  grep -Fq "${profile_key}=%s" "$bootstrap_lib" || \
    fail "resume run-context omits $profile_key"
done

printf '%s\n' 'Local certification explicit skip semantics are valid.'
