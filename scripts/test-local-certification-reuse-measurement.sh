#!/usr/bin/env bash
set -Eeuo pipefail
IFS=$'\n\t'

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/measure-local-certification-reuse.sh
source "$script_dir/measure-local-certification-reuse.sh"

fail() {
  printf 'certification reuse measurement contract failed: %s\n' "$*" >&2
  exit 1
}

fixture_root="$(mktemp -d)"
trap 'rm -rf "$fixture_root"' EXIT

bash -n "$script_dir/measure-local-certification-reuse.sh"

cold_plan="$fixture_root/cold-plan.json"
warm_plan="$fixture_root/warm-plan.json"
cold_manifest="$fixture_root/cold-manifest.json"
warm_manifest="$fixture_root/warm-manifest.json"

cat >"$cold_plan" <<'JSON'
{
  "schemaVersion": 1,
  "phases": [
    {"phaseId":"source-preflight","policy":"FRESH","decision":"EXECUTE","lookupDurationMillis":0,"revalidationDurationMillis":0},
    {"phaseId":"static-kubernetes-overlays","policy":"CONTENT_ADDRESSED","decision":"EXECUTE","lookupDurationMillis":1,"revalidationDurationMillis":0},
    {"phaseId":"registry-publish/quickfix-gateway","policy":"REVALIDATE","decision":"EXECUTE","lookupDurationMillis":1,"revalidationDurationMillis":0},
    {"phaseId":"kafka-broker-failure-live","policy":"FRESH","decision":"EXECUTE","lookupDurationMillis":0,"revalidationDurationMillis":0},
    {"phaseId":"kubernetes-workloads","policy":"FRESH","decision":"EXECUTE","lookupDurationMillis":0,"revalidationDurationMillis":0}
  ]
}
JSON

cat >"$warm_plan" <<'JSON'
{
  "schemaVersion": 1,
  "phases": [
    {"phaseId":"source-preflight","policy":"FRESH","decision":"EXECUTE","lookupDurationMillis":0,"revalidationDurationMillis":0},
    {"phaseId":"static-kubernetes-overlays","policy":"CONTENT_ADDRESSED","decision":"REUSE","lookupDurationMillis":2,"revalidationDurationMillis":0},
    {"phaseId":"registry-publish/quickfix-gateway","policy":"REVALIDATE","decision":"REVALIDATE","lookupDurationMillis":2,"revalidationDurationMillis":3},
    {"phaseId":"kafka-broker-failure-live","policy":"FRESH","decision":"EXECUTE","lookupDurationMillis":0,"revalidationDurationMillis":0},
    {"phaseId":"kubernetes-workloads","policy":"FRESH","decision":"EXECUTE","lookupDurationMillis":0,"revalidationDurationMillis":0}
  ]
}
JSON

cat >"$cold_manifest" <<'JSON'
{
  "schemaVersion": 1,
  "phases": [
    {"phaseId":"source-preflight","decision":"EXECUTED","execution":{"durationMillis":100}},
    {"phaseId":"static-kubernetes-overlays","decision":"EXECUTED","execution":{"durationMillis":200}},
    {"phaseId":"registry-publish/quickfix-gateway","decision":"EXECUTED","execution":{"durationMillis":300}},
    {"phaseId":"kafka-broker-failure-live","decision":"EXECUTED","execution":{"durationMillis":500}},
    {"phaseId":"kubernetes-workloads","decision":"EXECUTED","execution":{"durationMillis":400}}
  ]
}
JSON

cat >"$warm_manifest" <<'JSON'
{
  "schemaVersion": 1,
  "phases": [
    {"phaseId":"source-preflight","decision":"EXECUTED","execution":{"durationMillis":100}},
    {"phaseId":"static-kubernetes-overlays","decision":"REUSED","execution":{"durationMillis":5}},
    {"phaseId":"registry-publish/quickfix-gateway","decision":"REVALIDATED","execution":{"durationMillis":10}},
    {"phaseId":"kafka-broker-failure-live","decision":"EXECUTED","execution":{"durationMillis":500}},
    {"phaseId":"kubernetes-workloads","decision":"EXECUTED","execution":{"durationMillis":300}}
  ]
}
JSON

certification_benchmark_validate_plan cold "$cold_plan" ||
  fail 'valid cold plan was rejected'
certification_benchmark_validate_plan warm "$warm_plan" ||
  fail 'valid warm plan was rejected'

invalid_warm="$fixture_root/invalid-warm.json"
jq '(.phases[] | select(.phaseId == "static-kubernetes-overlays")).decision = "EXECUTE"' \
  "$warm_plan" >"$invalid_warm"
if certification_benchmark_validate_plan warm "$invalid_warm"; then
  fail 'warm plan accepted execution of reusable static evidence'
fi

jq '(.phases[] | select(.phaseId == "kubernetes-workloads")).decision = "REUSE"' \
  "$warm_plan" >"$invalid_warm"
if certification_benchmark_validate_plan warm "$invalid_warm"; then
  fail 'warm plan accepted reuse of a FRESH runtime phase'
fi

cold_phases="$fixture_root/cold-phases.json"
warm_phases="$fixture_root/warm-phases.json"
summary="$fixture_root/summary.json"
report="$fixture_root/report.md"

certification_benchmark_build_phase_table \
  "$cold_plan" "$cold_manifest" "$cold_phases" ||
  fail 'cold phase table could not be built'
certification_benchmark_build_phase_table \
  "$warm_plan" "$warm_manifest" "$warm_phases" ||
  fail 'warm phase table could not be built'

declare -A benchmark_context=(
  [sourceRevision]=0123456789abcdef0123456789abcdef01234567
  [tradingDay]=2026-08-27
  [imageTag]=local
  [imageTransport]=registry
  [coldElapsedMillis]=2000
  [warmElapsedMillis]=1200
  [coldPhases]="$cold_phases"
  [warmPhases]="$warm_phases"
  [summaryFile]="$summary"
  [reportFile]="$report"
)

certification_benchmark_build_summary benchmark_context ||
  fail 'measurement summary could not be built'
certification_benchmark_write_report benchmark_context ||
  fail 'measurement report could not be written'

jq -e '
  .schemaVersion == 2 and
  .acceptanceVerdict == "PASS" and
  .acceptanceCriterion == "NON_FRESH_WALL_CLOCK_NOT_DOMINANT" and
  .wallClock.observation == "IMPROVED" and
  .wallClock.savedMillis == 800 and
  .wallClock.reductionPercent == 40 and
  .wallClock.samplePairs == 1 and
  .wallClock.statisticalClaim == false and
  .cold.decisionCounts.EXECUTE == 5 and
  .warm.decisionCounts.EXECUTE == 3 and
  .warm.decisionCounts.REUSE == 1 and
  .warm.decisionCounts.REVALIDATE == 1 and
  .warm.freshPhaseMillis == 900 and
  .warm.recordedReusablePhaseMillis == 15 and
  .warm.nonFreshWallClockMillis == 300 and
  .warm.nonFreshWallClockSharePercent == 25 and
  .warm.nonFreshWallClockDominates == false and
  .environmentFault.rankAmongFreshPhases == 1 and
  .environmentFault.shareOfFreshExecutionPercent == 55.55 and
  .environmentFault.dominatesFreshExecution == true and
  .environmentFault.followUpIdentitySpecificationRecommended == true
' "$summary" >/dev/null || fail 'measurement summary has incorrect semantics'

grep -Fxq -- '- acceptance_verdict: PASS' "$report" ||
  fail 'measurement report omitted acceptance verdict'
grep -Fxq -- '- wall_clock_observation: IMPROVED' "$report" ||
  fail 'measurement report omitted wall-clock observation'
grep -Fq 'single-pair observation' "$report" ||
  fail 'measurement report overstated one wall-clock pair as statistical proof'

# Being the largest FRESH phase is not sufficient to justify environment reuse.
largest_only_phases="$fixture_root/largest-only-phases.json"
largest_only_summary="$fixture_root/largest-only-summary.json"
jq '
  map(
    if .phaseId == "source-preflight" then .durationMillis = 280
    elif .phaseId == "kafka-broker-failure-live" then .durationMillis = 300
    elif .phaseId == "kubernetes-workloads" then .durationMillis = 290
    else .
    end
  )
' "$warm_phases" >"$largest_only_phases"
benchmark_context[warmPhases]="$largest_only_phases"
benchmark_context[summaryFile]="$largest_only_summary"
certification_benchmark_build_summary benchmark_context ||
  fail 'largest-only environment summary could not be built'
jq -e '
  .environmentFault.rankAmongFreshPhases == 1 and
  .environmentFault.dominatesFreshExecution == false and
  .environmentFault.followUpIdentitySpecificationRecommended == false
' "$largest_only_summary" >/dev/null ||
  fail 'largest FRESH phase was incorrectly treated as dominant'

# A faster warm sample cannot hide non-FRESH wall-clock overhead that still
# dominates the warm run.
dominated_summary="$fixture_root/dominated-summary.json"
benchmark_context[coldElapsedMillis]=2500
benchmark_context[warmElapsedMillis]=1900
benchmark_context[warmPhases]="$warm_phases"
benchmark_context[summaryFile]="$dominated_summary"
certification_benchmark_build_summary benchmark_context ||
  fail 'dominated non-FRESH wall-clock summary could not be built'
jq -e '
  .wallClock.observation == "IMPROVED" and
  .warm.freshPhaseMillis == 900 and
  .warm.nonFreshWallClockMillis == 1000 and
  .warm.nonFreshWallClockDominates == true and
  .acceptanceVerdict == "FAIL"
' "$dominated_summary" >/dev/null ||
  fail 'wall-clock improvement hid dominant non-FRESH overhead'

# One cold/warm pair remains an observation rather than a statistical claim.
# A small wall-clock regression does not redefine the composition criterion.
regressed_summary="$fixture_root/regressed-summary.json"
benchmark_context[coldElapsedMillis]=1500
benchmark_context[warmElapsedMillis]=1600
benchmark_context[summaryFile]="$regressed_summary"
certification_benchmark_build_summary benchmark_context ||
  fail 'regressed wall-clock summary could not be built'
jq -e '
  .acceptanceVerdict == "PASS" and
  .warm.nonFreshWallClockMillis == 700 and
  .warm.nonFreshWallClockDominates == false and
  .wallClock.observation == "REGRESSED" and
  .wallClock.statisticalClaim == false
' "$regressed_summary" >/dev/null ||
  fail 'single-pair wall-clock noise was treated as a statistical verdict'

# The run adapter receives one measurement context instead of ten positional
# arguments, and ambient Kafka evidence paths cannot leak into measured runs.
fake_runner="$fixture_root/fake-certification-runner.sh"
capture_file="$fixture_root/runner-environment.txt"
fake_evidence="$fixture_root/fake-evidence"
cat >"$fake_runner" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
mkdir -p "$SIMPLEMATCH_CERTIFICATION_EVIDENCE_DIR"
printf '%s\n' '# fixture' '- status: PASSED' \
  >"$SIMPLEMATCH_CERTIFICATION_EVIDENCE_DIR/report.md"
printf '%s|%s|%s\n' \
  "${SIMPLEMATCH_KAFKA_PRODUCER_CONFIG_FILE-unset}" \
  "${SIMPLEMATCH_KAFKA_CAPACITY_EVIDENCE_FILE-unset}" \
  "$SIMPLEMATCH_MARKET_REFERENCE_DELIVERY_MANIFEST" \
  >"$TEST_CAPTURE_FILE"
SH
chmod 0755 "$fake_runner"
certification_runner="$fake_runner"
declare -A fixture_context=(
  [cacheDir]="$fixture_root/fake-cache"
  [fixtureEvidenceDir]="$fake_evidence"
  [fixtureNamespace]=fixture-namespace
  [fixtureComposeProject]=fixture-compose
  [fixtureLog]="$fixture_root/fake.log"
  [tradingDay]=2026-08-27
  [deliveryManifest]=/approved/market-reference.yaml
  [imageTag]=local
  [imageTransport]=registry
)
export TEST_CAPTURE_FILE="$capture_file"
export SIMPLEMATCH_KAFKA_PRODUCER_CONFIG_FILE="$fixture_root/ambient-producer.txt"
export SIMPLEMATCH_KAFKA_CAPACITY_EVIDENCE_FILE="$fixture_root/ambient-capacity.txt"
certification_benchmark_run_once fixture_context fixture ||
  fail 'fixture measured run failed'
unset SIMPLEMATCH_KAFKA_PRODUCER_CONFIG_FILE
unset SIMPLEMATCH_KAFKA_CAPACITY_EVIDENCE_FILE
unset TEST_CAPTURE_FILE

grep -Fxq 'unset|unset|/approved/market-reference.yaml' "$capture_file" ||
  fail 'measured run leaked ambient Kafka evidence or changed Market Reference input'

printf '%s\n' 'Local certification reuse measurement contracts are valid.'