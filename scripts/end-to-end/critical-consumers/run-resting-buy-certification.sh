#!/usr/bin/env bash
set -Eeuo pipefail
IFS=$'\n\t'

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd -- "$script_dir/../../.." && pwd)"
# shellcheck source=scripts/lib/local-common.sh
source "$repo_root/scripts/lib/local-common.sh"
# shellcheck source=scripts/lib/local-kind.sh
source "$repo_root/scripts/lib/local-kind.sh"
# shellcheck source=scripts/end-to-end/critical-consumers/lib/failure-support.sh
source "$script_dir/lib/failure-support.sh"
# shellcheck source=scripts/end-to-end/critical-consumers/lib/gateway-owner-recovery.sh
source "$script_dir/lib/gateway-owner-recovery.sh"
# shellcheck source=scripts/end-to-end/critical-consumers/lib/matching-business-recovery.sh
source "$script_dir/lib/matching-business-recovery.sh"

context="kind-${SIMPLEMATCH_KIND_CLUSTER_NAME:-simplematch-live}"
namespace=""
evidence_dir=""
timeout_seconds=180
retained_evidence_dir="$(simplematch_production_like_evidence_dir "$repo_root")"
kafka_observer_pod="critical-consumer-kafka-observer"
kafka_observer_manifest="$repo_root/deploy/k8s/verification/critical-consumer-kafka-observer-pod.yaml"
kafka_observer_created=false
gateway_env_modified=false
restoration_failed=false
evidence_initialized=false
fix_state_dir=""
gateway_recovery=false
matching_recovery=false
current_stage=preflight

die() { printf 'resting-buy certification: %s\n' "$*" >&2; exit 1; }

cleanup() {
  local status="$?"
  trap - EXIT ERR
  set +e
  timeout_seconds="${matching_recovery_original_timeout_seconds:-${gateway_recovery_original_timeout_seconds:-$timeout_seconds}}"
  kubernetes_request_timeout_seconds=""
  operation_deadline_epoch_ms=""
  stop_background_process "${gateway_owner_sampler_pid:-}" || restoration_failed=true
  stop_background_process "${fix_submit_pid:-}" || restoration_failed=true
  stop_fix_port_forward || restoration_failed=true
  stop_gateway_port_forward || restoration_failed=true
  stop_kafka_observation_adapter || restoration_failed=true
  delete_kafka_observer_pod || restoration_failed=true
  restore_gateway_environment
  if [[ ( "$gateway_recovery" == true || "$matching_recovery" == true ) && "$evidence_initialized" == true &&
      -n "$fix_state_dir" && "$restoration_failed" == false ]]; then
    mkdir -p "$evidence_dir/recovery"
    capture_restored_gateway_readiness || restoration_failed=true
    stop_gateway_port_forward || restoration_failed=true
  fi
  # This exact mktemp directory contains temporary raw FIX stores/logs, not evidence.
  if [[ -n "$fix_state_dir" ]]; then
    rm -rf -- "$fix_state_dir" || restoration_failed=true
  fi
  if [[ "$evidence_initialized" == true ]]; then
    if [[ -n "${gateway_operator_token:-}" ]] &&
        rg -q --fixed-strings -- "$gateway_operator_token" "$evidence_dir"; then
      restoration_failed=true
    fi
    ruby "$script_dir/lib/resting-buy-verification.rb" finalize \
      "$evidence_dir" "$status" "$current_stage" "$restoration_failed" "$gateway_recovery" "$matching_recovery" || status=1
  fi
  [[ "$restoration_failed" == false ]] || status=1
  exit "$status"
}
trap cleanup EXIT

while (($# > 0)); do
  case "$1" in
    --namespace) namespace="${2:?namespace required}"; shift 2 ;;
    --evidence-dir) evidence_dir="${2:?evidence directory required}"; shift 2 ;;
    --timeout-seconds) timeout_seconds="${2:?timeout required}"; shift 2 ;;
    --gateway-recovery) gateway_recovery=true; shift ;;
    --matching-recovery) matching_recovery=true; shift ;;
    --help|-h)
      printf '%s\n' 'Usage: run-resting-buy-certification.sh --namespace NAME --evidence-dir PATH [--timeout-seconds 180] [--gateway-recovery | --matching-recovery]'
      exit 0 ;;
    *) die "unknown option: $1" ;;
  esac
done
[[ -n "$namespace" && -n "$evidence_dir" ]] || die '--namespace and --evidence-dir are required'
[[ "$gateway_recovery" != true || "$matching_recovery" != true ]] || die 'choose one recovery scenario'
if [[ ! "$timeout_seconds" =~ ^[1-9][0-9]*$ ]] || (( timeout_seconds > 300 )); then
  die 'timeout must be 1..300 seconds'
fi
for tool in docker kubectl ruby jq curl timeout rg; do
  command -v "$tool" >/dev/null || die "$tool is required"
done
[[ "$(kubectl config current-context)" == "$context" ]] || die "current context must be $context"
simplematch_kind_namespace_is_disposable "$context" "$namespace" local-production-like-certification ||
  die 'namespace is not a run-owned disposable deployment'
simplematch_certification_verifier_image "$repo_root" "$namespace" "$retained_evidence_dir" >/dev/null ||
  die 'deployment source/verifier provenance does not match the current source'
retained_run_id="$(awk -F= '$1 == "run_id" {print substr($0, index($0, "=") + 1)}' "$retained_evidence_dir/run-context")"
namespace_run_id="$(kns get namespace "$namespace" -o jsonpath='{.metadata.labels.simplematch\.io/run-id}')"
[[ -n "$retained_run_id" && "$namespace_run_id" == "$retained_run_id" ]] || die 'namespace run-id mismatch'
deployment_prerequisites="$(ruby "$script_dir/lib/resting-buy-verification.rb" deployment "$retained_evidence_dir")" ||
  die 'retained deployment did not complete the required trading prerequisites'
mkdir -p "$evidence_dir"
evidence_dir="$(cd -- "$evidence_dir" && pwd)"
[[ -z "$(ls -A "$evidence_dir")" ]] || die 'evidence directory must be empty'
mkdir -p "$evidence_dir/baseline" "$evidence_dir/submission" "$evidence_dir/fix" "$evidence_dir/kafka"
cp "$retained_evidence_dir/source-revision" "$evidence_dir/source-revision"
evidence_initialized=true
printf '%s\n' "$deployment_prerequisites" >"$evidence_dir/baseline/deployment-prerequisites.json"
docker info >/dev/null || die 'Docker daemon is unavailable'
simplematch_kind_validate_canonical_topology "$context" "$evidence_dir/baseline/nodes.json" || die 'canonical topology is not ready'
simplematch_kind_validate_control_plane_stability "$context" 5 60 "$evidence_dir/baseline/control-plane" 60 || die 'control plane is unstable'
fix_state_dir="$(mktemp -d "${TMPDIR:-/tmp}/simplematch-resting-fix.XXXXXX")"

current_stage='select observed instrument and fund isolated account'
select_market_input
account_id="$(< /proc/sys/kernel/random/uuid)"
cl_ord_id="REST-$(date -u +%Y%m%d%H%M%S)-${account_id:0:8}"
seed_account_limit "$evidence_dir/submission/account-fixture.log"
ruby "$script_dir/lib/resting-buy-verification.rb" prepare "$evidence_dir" \
  "$account_id" "$cl_ord_id" "$quantity" "$trading_day" "$trading_session_id" \
  "$artifact_checksum" "$routing_algorithm_version"

current_stage='prepare real FIX and live Gateway control'
live_fix_side=1
live_fix_time_in_force=0
enable_gateway_operations true
start_gateway_port_forward
start_fix_port_forward
start_fix_submit_client || die 'prepared FIX client did not log on'
start_kafka_observation_adapter "$retained_evidence_dir"
capture_kafka_log_end_positions "$evidence_dir/baseline/commands-before.json" "$evidence_dir/baseline/events-before.json"

current_stage='wait for production live readiness and operator open'
cp "$script_dir/tests/fixtures/resting-buy-open-request.json" "$evidence_dir/baseline/open-request.json"
open_gateway_from_live_observations "$evidence_dir/baseline/open-request.json" \
  "$evidence_dir/baseline/gateway-before.json" "$evidence_dir/baseline/gateway-open.json" ||
  die 'Gateway did not accept real operator open before the live-readiness deadline'

current_stage='submit one real FIX order and observe durable Risk admission'
release_fix_submit_client
if [[ "$gateway_recovery" == true || "$matching_recovery" == true ]]; then
  wait_fix_submission_evidence || die 'retained FIX submission failed'
else
  wait_fix_submit_client || die 'FIX submission failed'
fi
require_fix_submission_accepted "$evidence_dir/fix/submit.json"
postgres="$(postgres_pod)"
kns exec -i "$postgres" -c postgres -- psql -U simplematch -d simplematch -At \
  -v ON_ERROR_STOP=1 -v account_id="$account_id" -v cl_ord_id="$cl_ord_id" -f - \
  <"$script_dir/sql/resting-buy-admission.sql" >"$evidence_dir/submission/risk-admission.json"
command_id="$(jq -er '.commandId' "$evidence_dir/submission/risk-admission.json")"
order_id="$(jq -er '.orderId' "$evidence_dir/submission/risk-admission.json")"
partition="$(jq -er '.routingPartition' "$evidence_dir/submission/risk-admission.json")"
start_offset="$(jq -er --argjson partition "$partition" '.partitions[] | select(.partition == $partition) | .offset' "$evidence_dir/baseline/events-before.json")"

current_stage='correlate real Kafka command and ORDER_RESTED'
kns exec -i "$kafka_observer_pod" -c observer -- sh -c 'tee /tmp/commands-before.json >/dev/null' \
  <"$evidence_dir/baseline/commands-before.json"
kns exec "$kafka_observer_pod" -c observer -- java -cp '/app/lib/*' \
  com.simplematch.tools.riskmatchinge2e.MatchingEventObservationMain \
  --bootstrap kafka:9092 --topic matching.events --partition "$partition" \
  --start-offset "$start_offset" --command-id "$command_id" --order-id "$order_id" \
  --commands-before /tmp/commands-before.json --timeout-seconds "$timeout_seconds" --evidence-dir /tmp/resting-buy
for name in matching-command-observation matching-event-observation matching-event-observer-verdict; do
  kns exec "$kafka_observer_pod" -c observer -- cat "/tmp/resting-buy/$name.json" >"$evidence_dir/kafka/$name.json"
done

current_stage='verify Persistence and Account business effects'
event_id="$(jq -er '.eventId' "$evidence_dir/kafka/matching-event-observation.json")"
event_offset="$(jq -er '.offset' "$evidence_dir/kafka/matching-event-observation.json")"
wait_consumers_through "$partition" "$event_offset"
kns exec -i "$postgres" -c postgres -- psql -U simplematch -d simplematch -At \
  -v ON_ERROR_STOP=1 -v account_id="$account_id" -v order_id="$order_id" \
  -v trading_day="$trading_day" -v event_id="$event_id" -f - \
  <"$script_dir/sql/resting-buy-durable-state.sql" >"$evidence_dir/durable-state.json"
wait_gateway_live_open "$evidence_dir/baseline/gateway-after.json" || die 'Gateway did not remain healthy and OPEN'
ruby "$script_dir/lib/resting-buy-verification.rb" verify "$evidence_dir"
if [[ "$gateway_recovery" == true ]]; then
  run_gateway_owner_recovery
fi
if [[ "$matching_recovery" == true ]]; then
  run_matching_business_recovery
fi
current_stage=completed
