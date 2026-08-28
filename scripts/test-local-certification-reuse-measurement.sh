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
certification_benchmark_build_summary \
  0123456789abcdef0123456789abcdef01234567 2026-08-27 local registry \
  2000 1200 "$cold_phases" "$warm_phases" "$summary" ||
  fail 'measurement summary could not be built'
certification_benchmark_write_report "$summary" "$report" ||
  fail 'measurement report could not be written'

jq -e '
  .verdict == "PASS" and
  .wallClock.savedMillis == 800 and
  .wallClock.reductionPercent == 40 and
  .cold.decisionCounts.EXECUTE == 5 and
  .warm.decisionCounts.EXECUTE == 3 and
  .warm.decisionCounts.REUSE == 1 and
  .warm.decisionCounts.REVALIDATE == 1 and
  .environmentFault.rankAmongFreshPhases == 1 and
  .environmentFault.largestFreshPhase == "kafka-broker-failure-live" and
  .environmentFault.followUpIdentitySpecificationRecommended == true
' "$summary" >/dev/null || fail 'measurement summary has incorrect semantics'

grep -Fxq -- '- reduction_percent: 40' "$report" ||
  fail 'measurement report omitted wall-clock reduction'
grep -Fq 'Kafka broker-failure verification was the largest recorded fresh phase.' \
  "$report" || fail 'measurement report omitted environment-fault interpretation'

# The wrapper owns the experiment inputs. Ambient paths that tell the normal
# runner to consume externally supplied Kafka evidence must not leak into a
# measured run, while the selected Market Reference manifest must be pinned.
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
export TEST_CAPTURE_FILE="$capture_file"
export SIMPLEMATCH_KAFKA_PRODUCER_CONFIG_FILE="$fixture_root/ambient-producer.txt"
export SIMPLEMATCH_KAFKA_CAPACITY_EVIDENCE_FILE="$fixture_root/ambient-capacity.txt"
certification_benchmark_run_once \
  fixture "$fake_evidence" fixture-namespace fixture-compose \
  "$fixture_root/fake-cache" 2026-08-27 local registry \
  "$fixture_root/fake.log" /approved/market-reference.yaml ||
  fail 'fixture measured run failed'
unset SIMPLEMATCH_KAFKA_PRODUCER_CONFIG_FILE
unset SIMPLEMATCH_KAFKA_CAPACITY_EVIDENCE_FILE
unset TEST_CAPTURE_FILE

grep -Fxq 'unset|unset|/approved/market-reference.yaml' "$capture_file" ||
  fail 'measured run leaked ambient Kafka evidence or changed Market Reference input'

printf '%s\n' 'Local certification reuse measurement contracts are valid.'
