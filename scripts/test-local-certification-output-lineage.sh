#!/usr/bin/env bash
set -Eeuo pipefail
IFS=$'\n\t'

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd -- "$script_dir/.." && pwd)"

# shellcheck source=scripts/lib/local-certification-phase-graph.sh
source "$script_dir/lib/local-certification-phase-graph.sh"
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
  printf 'certification output-lineage contract failed: %s\n' "$*" >&2
  exit 1
}

assert_eq() {
  local expected="$1"
  local actual="$2"
  local message="$3"
  [[ "$actual" == "$expected" ]] || \
    fail "$message: expected=$expected actual=$actual"
}

fixture_root="$(mktemp -d)"
trap 'rm -rf "$fixture_root"' EXIT

export SIMPLEMATCH_CERTIFICATION_CACHE_DIR="$fixture_root/cache"
evidence_dir="$fixture_root/run"
matching_producer_config_file="$evidence_dir/matching-producer.config.txt"
mkdir -p "$(dirname -- "$matching_producer_config_file")" \
  "$evidence_dir/phases/kafka-producer-contract"
printf '%s\n' 'acks=all' 'enable.idempotence=true' \
  >"$matching_producer_config_file"

outputs="$(certification_phase_outputs_json kafka-producer-contract)" || \
  fail 'producer configuration output could not be described'
input_fingerprint="sha256:$(printf producer-config | sha256sum | awk '{print $1}')"
result_path="$evidence_dir/phases/kafka-producer-contract/result.json"
jq -n \
  --arg input "$input_fingerprint" \
  --argjson outputs "$outputs" '{
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
  }' >"$result_path"

assert_eq RESUME \
  "$(certification_phase_resume_decision kafka-producer-contract)" \
  'same-run result did not accept its current producer configuration'

rm -f -- "$matching_producer_config_file"
assert_eq REEXECUTE \
  "$(certification_phase_resume_decision kafka-producer-contract)" \
  'same-run result accepted a missing producer configuration'

evidence_digest="$(certification_evidence_publish \
  kafka-producer-contract "$input_fingerprint" "$result_path")" || \
  fail 'producer configuration evidence could not be published'
certification_phase_materialize_reused_outputs \
  kafka-producer-contract "$evidence_digest" || \
  fail 'producer configuration could not be materialized from reusable evidence'
grep -Fxq 'acks=all' "$matching_producer_config_file" || \
  fail 'materialized producer configuration content is incomplete'
assert_eq RESUME \
  "$(certification_phase_resume_decision kafka-producer-contract)" \
  'materialized producer configuration did not restore same-run validity'

# Runtime CDC evidence is also a content-addressed phase output.  Every file
# required by the observer must be present and represented with a digest and
# materialized content; a partial outage report must fail closed.
cdc_evidence_dir="$evidence_dir/cdc-delivery"
mkdir -p "$cdc_evidence_dir"
for cdc_file in \
  verdict.json event.json connector-running-before.json connector-paused.json \
  connectors-running-before.json connector-recovered.json \
  connectors-running-recovered.json health-liveness.json health-readiness.json \
  metric-before-lag.json metric-before-age.json metric-before-updated-at.json \
  metric-baseline-age.txt metric-baseline-updated-at.json \
  metric-zero-traffic-updated-at.json \
  metric-paused-lag.json \
  metric-paused-age.json metric-paused-updated-at.json \
  metric-recovered-lag.json metric-recovered-age.json metric-recovered-updated-at.json \
  metric-paused-row.txt metric-recovered-row.txt observation-row.txt \
  account-service.log persistence.log market-data-projection.log \
  marketdata-streamer.log query-service.log quickfix-gateway.log risk-service.log; do
  printf 'evidence:%s\n' "$cdc_file" >"$cdc_evidence_dir/$cdc_file"
done
cdc_outputs="$(certification_phase_outputs_json kubernetes-cdc-delivery)" || \
  fail 'Risk CDC evidence output could not be described'
jq -e '
  length == 31 and
  all(.[];
    .kind == "file-content" and
    (.name | startswith("risk-cdc-delivery-")) and
    (.identity | test("^sha256:[0-9a-f]{64}$")) and
    (.location | startswith("cdc-delivery/")) and
    (.contentBase64 | type == "string" and length > 0))
' <<<"$cdc_outputs" >/dev/null || fail 'Risk CDC evidence output is incomplete'
rm -f -- "$cdc_evidence_dir/risk-service.log"
if certification_phase_outputs_json kubernetes-cdc-delivery >/dev/null; then
  fail 'Risk CDC evidence output accepted a missing safe-log report'
fi

printf '%s\n' 'Local certification output lineage contracts are valid.'
