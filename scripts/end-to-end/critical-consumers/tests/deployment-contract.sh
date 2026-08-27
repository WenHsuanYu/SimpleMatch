#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd -- "$script_dir/../../../.." && pwd)"
canonical_runner="$script_dir/../run-failure-certification.sh"
compatibility_runner="$repo_root/scripts/run-critical-consumer-failure-certification.sh"
matching_module="$script_dir/../lib/matching-status.sh"
observation_module="$script_dir/../lib/system-observation.sh"
support_module="$script_dir/../lib/failure-support.sh"
cluster_module="$script_dir/../lib/cluster-data.sh"
interfaces_module="$script_dir/../lib/test-interfaces.sh"
kafka_interface="$script_dir/../lib/kafka-observation-interface.sh"
recovery_module="$script_dir/../lib/failure-recovery.sh"
observer_manifest="$repo_root/deploy/k8s/verification/matching-event-observer-pod.yaml"
kafka_observer_manifest="$repo_root/deploy/k8s/verification/critical-consumer-kafka-observer-pod.yaml"
quickfix_build="$repo_root/services/quickfix-gateway/build.gradle.kts"

fail() {
  printf 'Critical consumer deployment contract: %s\n' "$*" >&2
  exit 1
}

for script in \
  "$canonical_runner" \
  "$compatibility_runner" \
  "$matching_module" \
  "$observation_module" \
  "$support_module" \
  "$cluster_module" \
  "$interfaces_module" \
  "$kafka_interface" \
  "$recovery_module"; do
  [[ -r "$script" ]] || fail "required script is missing: $script"
  bash -n "$script"
done
[[ -x "$canonical_runner" ]] || fail 'canonical failure certification must be executable'
[[ -x "$compatibility_runner" ]] || fail 'compatibility runner must be executable'
"$compatibility_runner" --help >/dev/null

grep -Fq 'end-to-end/critical-consumers/run-failure-certification.sh' "$compatibility_runner" ||
  fail 'legacy runner must delegate to the end-to-end certification entrypoint'
grep -Fq 'capture_matching_samples_parallel' "$observation_module" ||
  fail 'Matching runtime samples must be collected in parallel across partitions'
grep -Fq 'capture_kafka_log_end_positions' "$observation_module" ||
  fail 'system observation must use the reusable Kafka observation interface'
grep -Fq 'capture_kafka_matching_committed_positions' "$observation_module" ||
  fail 'Matching committed positions must use the reusable Kafka observation interface'
if grep -Fq 'capture_topic_offsets' "$observation_module" ||
   grep -Fq 'capture_matching_committed_offsets' "$observation_module"; then
  fail 'freshness-sensitive observation must not launch Kafka command-line snapshots'
fi
grep -Fq 'updated_at_epoch_ms' "$matching_module" ||
  fail 'Matching status must retain the runtime source timestamp'
grep -Fq 'matching-commands-before.json' "$observation_module" ||
  fail 'system observation must capture Kafka positions before collection'
grep -Fq 'matching-commands-after.json' "$observation_module" ||
  fail 'system observation must verify Kafka positions after collection'
grep -Fq 'EVIDENCE_EXPIRED_DURING_COLLECTION' "$observation_module" ||
  fail 'collector-induced expiration must have a distinct failure classification'
grep -Fq 'SOURCE_ALREADY_STALE' "$observation_module" ||
  fail 'source-side staleness must have a distinct failure classification'
grep -Fq 'ageAddedByCollectorMillis' "$observation_module" ||
  fail 'timing evidence must expose age added after Matching sampling'
grep -Fq 'partition-$partition-pod-before.json' "$observation_module" ||
  fail 'Matching collection must record Pod identity before runtime read'
grep -Fq 'partition-$partition-pod-after.json' "$observation_module" ||
  fail 'Matching collection must record Pod identity after runtime read'
grep -Fq 'persistenceQuarantineHistory' "$cluster_module" ||
  fail 'baseline evidence must include quarantine history'
grep -Fq 'preparedSubmissionCertificationTest' "$interfaces_module" ||
  fail 'FIX submission must use the prepared-client barrier'
grep -Fq 'SIMPLEMATCH_RETAINED_FIX_RELEASE_FILE' "$interfaces_module" ||
  fail 'prepared FIX submission must wait for an explicit release signal'
grep -Fq 'gateway-submission-started-at' "$interfaces_module" ||
  fail 'Gateway observation submission start must be retained'
grep -Fq 'gateway-submission-completed-at' "$interfaces_module" ||
  fail 'Gateway observation submission completion must be retained'
grep -Fq 'oldestSourceAgeAtSubmissionCompletionMillis' "$interfaces_module" ||
  fail 'Gateway submission timing must retain source age at completion'
grep -Fq 'archive_gateway_observation_attempts' "$interfaces_module" ||
  fail 'Gateway retries must archive prior collection evidence before reuse'
grep -Fq 'start_kafka_observation_adapter' "$canonical_runner" ||
  fail 'canonical runner must prepare the warm Kafka observation adapter'
grep -Fq 'stop_kafka_observation_adapter' "$canonical_runner" ||
  fail 'canonical runner must stop the Kafka observation adapter'
grep -Fq 'gateway-open-to-fix-send.json' "$canonical_runner" ||
  fail 'Gateway open-to-send timing evidence is missing'
grep -Fq 'kafka-observation-interface.sh' "$support_module" ||
  fail 'failure support must expose the Kafka observation interface'

if grep -Fq 'OPERATIONS_MONITOR_ENABLED=false' "$canonical_runner" "$interfaces_module"; then
  fail 'failure certification must not disable the Gateway stale-observation monitor'
fi
if grep -Fq '.next_commit_offset' "$canonical_runner" "$observation_module" "$cluster_module" "$interfaces_module" "$recovery_module"; then
  fail 'durable Matching progress must come from Kafka, not next_commit_offset'
fi
if grep -Fq 'TZ=Asia/Taipei date +%F' "$canonical_runner" "$interfaces_module"; then
  fail 'failure certification must use the deployed trading day'
fi
if grep -Fq '.instrument.symbol' "$canonical_runner" "$interfaces_module"; then
  fail 'failure certification must use the flat Market Reference schema'
fi

grep -Fq 'QuickFixPreparedSubmissionLiveCertificationTest' "$quickfix_build" ||
  fail 'QuickFIX prepared-submission test must be excluded from normal tests and have an opt-in task'
grep -Fq 'preparedSubmissionCertificationTest' "$quickfix_build" ||
  fail 'QuickFIX prepared-submission Gradle task is missing'

kafka_tmp="$(mktemp -d)"
submission_tmp="$(mktemp -d)"
trap 'rm -rf "$kafka_tmp" "$submission_tmp"' EXIT
(
  # shellcheck source=scripts/end-to-end/critical-consumers/lib/kafka-observation-interface.sh
  source "$kafka_interface"
  kafka_observation_request() {
    local path="$1"
    local destination="$2"
    case "$path" in
      /log-end-positions)
        jq -n '
          def parts($base): [range(0; 15) | {partition:., offset:($base + .)}];
          {
            matchingCommands:{topic:"matching.commands",partitions:parts(10)},
            matchingEvents:{topic:"matching.events",partitions:parts(20)}
          }
        ' >"$destination"
        ;;
      /matching-committed-positions)
        jq -n '
          {topic:"matching.commands",partitions:[
            range(0; 15) | {partition:., committedOffset:(30 + .)}
          ]}
        ' >"$destination"
        ;;
      *) return 1 ;;
    esac
  }
  capture_kafka_log_end_positions "$kafka_tmp/commands.json" "$kafka_tmp/events.json" ||
    fail 'Kafka interface rejected a valid log-end snapshot'
  capture_kafka_matching_committed_positions "$kafka_tmp/committed.json" ||
    fail 'Kafka interface rejected valid Matching committed positions'
  jq -e '.topic == "matching.commands" and .partitions[14].offset == 24' \
    "$kafka_tmp/commands.json" >/dev/null ||
    fail 'Kafka interface did not normalize matching.commands positions'
  jq -e '.topic == "matching.events" and .partitions[14].offset == 34' \
    "$kafka_tmp/events.json" >/dev/null ||
    fail 'Kafka interface did not normalize matching.events positions'
  jq -e '.partitions[14].committedOffset == 44' "$kafka_tmp/committed.json" >/dev/null ||
    fail 'Kafka interface did not retain Matching committed positions'
)

(
  # shellcheck source=scripts/end-to-end/critical-consumers/lib/test-interfaces.sh
  source "$interfaces_module"
  evidence_dir="$submission_tmp/evidence"
  retry_dir="$evidence_dir/baseline/observation-1-attempt-1"
  attempt_dir="$evidence_dir/baseline/observation-1-attempt-2"
  mkdir -p "$retry_dir" "$attempt_dir"
  printf '%s\n' '{"exitStatus":2}' >"$retry_dir/result.json"
  printf '%s\n' '{"exitStatus":0}' >"$attempt_dir/result.json"
  cat >"$attempt_dir/timing.json" <<'JSON'
{
  "matchingRuntimeFreshness": {
    "oldestSourceEpochMs": 10000,
    "maximumFactAgeMillis": 3500
  }
}
JSON
  printf '%s\n' 12000 >"$attempt_dir/gateway-submission-started-at"
  printf '%s\n' 12300 >"$attempt_dir/gateway-submission-completed-at"
  resolved="$(accepted_observation_attempt_dir \
    "$evidence_dir/baseline/gateway-observation-1.json")"
  [[ "$resolved" == "$attempt_dir" ]] ||
    fail 'Gateway submission timing must resolve the accepted collection attempt'
  record_gateway_observation_submission_timing "$attempt_dir" ||
    fail 'Gateway submission timing must update the accepted attempt evidence'
  jq -e '
    .gatewaySubmission.startedEpochMs == 12000
    and .gatewaySubmission.completedEpochMs == 12300
    and .gatewaySubmission.durationMillis == 300
    and .matchingRuntimeFreshness.oldestSourceAgeAtSubmissionStartMillis == 2000
    and .matchingRuntimeFreshness.oldestSourceAgeAtSubmissionCompletionMillis == 2300
    and .matchingRuntimeFreshness.remainingBudgetAtSubmissionStartMillis == 1500
    and .matchingRuntimeFreshness.remainingBudgetAtSubmissionCompletionMillis == 1200
  ' "$attempt_dir/timing.json" >/dev/null ||
    fail 'Gateway submission timing must expose source age and remaining freshness budget'

  response="$evidence_dir/baseline/gateway-observation-1-gateway-attempt-3.json"
  payload="$evidence_dir/baseline/gateway-observation-1.json"
  archive_gateway_observation_attempts "$response" "$payload" ||
    fail 'Gateway submission must archive collection evidence before another retry'
  [[ ! -e "$retry_dir" && ! -e "$attempt_dir" ]] ||
    fail 'archived collection attempts must not remain at reusable paths'
  [[ -f "$evidence_dir/baseline/observation-1-gateway-3-attempt-1/result.json" ]] ||
    fail 'retryable collection evidence must survive Gateway retry archival'
  archived="$evidence_dir/baseline/observation-1-gateway-3-attempt-2"
  [[ -f "$archived/result.json" && -f "$archived/timing.json" ]] ||
    fail 'accepted collection evidence must survive Gateway retry archival'
  jq -e '.gatewaySubmission.durationMillis == 300' "$archived/timing.json" >/dev/null ||
    fail 'archived accepted attempt must retain Gateway submission timing'
)

ruby - "$canonical_runner" <<'RUBY'
path = ARGV.fetch(0)
script = File.read(path, encoding: "UTF-8")
main = script.split('current_stage="capture original workload configuration"', 2).last
abort "failure certification main sequence was not found" if main == script
markers = [
  "start_fix_submit_client",
  "start_kafka_observation_adapter",
  "pause_risk_outbox",
  'submit_open_eligible_observation "$check"',
  "gateway_request POST /operations/open",
  "release_fix_submit_client",
  'capture_risk_admission "$evidence_dir/submission/risk-admission.json"',
  'require_matching_command_held "$evidence_dir/baseline/matching-commands-offsets.json" "$partition"',
  "scale_statefulset matching 0",
  'release_matching_command "$evidence_dir/baseline/matching-commands-offsets.json" "$partition"',
  "scale_deployment account-service 0",
  "scale_deployment persistence 0",
  "scale_statefulset quickfix-gateway 0",
  "scale_statefulset postgres 0",
  'scale_statefulset matching "$original_matching_replicas"'
]
positions = markers.map do |marker|
  position = main.index(marker)
  abort "failure certification is missing required step: #{marker}" unless position
  [marker, position]
end
positions.each_cons(2) do |left, right|
  abort "invalid ordering: #{left[0]} must precede #{right[0]}" unless left[1] < right[1]
end
puts "Critical consumer failure sequence preserves prepared admission before fault injection."
RUBY

ruby -r yaml - "$observer_manifest" <<'RUBY'
path = ARGV.fetch(0)
pod = YAML.safe_load(File.read(path, encoding: "UTF-8"))
abort "Matching Event observer must be a v1 Pod" unless pod["apiVersion"] == "v1" && pod["kind"] == "Pod"
spec = pod.fetch("spec")
abort "observer must never restart" unless spec["restartPolicy"] == "Never"
abort "observer must not receive an API token" unless spec["automountServiceAccountToken"] == false
container = spec.fetch("containers").fetch(0)
abort "observer must reuse typed verifier image" unless container["image"] == "simplematch/risk-matching-e2e-verifier:local"
abort "observer must remain available for exec/cp" unless container["command"] == ["sleep", "600"]
abort "observer root filesystem must be read-only" unless container.dig("securityContext", "readOnlyRootFilesystem") == true
abort "observer must drop all Linux capabilities" unless container.dig("securityContext", "capabilities", "drop") == ["ALL"]
RUBY

ruby -r yaml - "$kafka_observer_manifest" <<'RUBY'
path = ARGV.fetch(0)
pod = YAML.safe_load(File.read(path, encoding: "UTF-8"))
abort "Kafka observer must be a v1 Pod" unless pod["apiVersion"] == "v1" && pod["kind"] == "Pod"
spec = pod.fetch("spec")
abort "Kafka observer must never restart" unless spec["restartPolicy"] == "Never"
abort "Kafka observer must not receive an API token" unless spec["automountServiceAccountToken"] == false
container = spec.fetch("containers").fetch(0)
abort "Kafka observer must reuse typed verifier image" unless container["image"] == "simplematch/risk-matching-e2e-verifier:local"
command = container.fetch("command")
abort "Kafka observer must run the reusable observation server" unless command.include?("com.simplematch.tools.riskmatchinge2e.KafkaObservationServerMain")
abort "Kafka observer must expose the health check" unless container.dig("readinessProbe", "httpGet", "path") == "/health"
abort "Kafka observer root filesystem must be read-only" unless container.dig("securityContext", "readOnlyRootFilesystem") == true
abort "Kafka observer must drop all Linux capabilities" unless container.dig("securityContext", "capabilities", "drop") == ["ALL"]
puts "Critical consumer deployment contracts are valid."
RUBY
