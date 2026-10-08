#!/usr/bin/env bash

# One routed Matching owner, the original resting order, and one new cancel.
# All steps share a deadline; raw Kubernetes resources remain in private scratch.

matching_recovery_remaining_seconds() {
  local remaining=$((matching_recovery_deadline_ms - $(date +%s%3N)))
  (( remaining > 0 )) || die 'Matching business recovery exceeded its single deadline'
  printf '%s\n' "$(((remaining + 999) / 1000))"
}

capture_matching_owner() {
  local phase="$1" claim volume
  local private_dir="$fix_state_dir/matching-owner"
  mkdir -p "$private_dir"
  kns get pod "$matching_owner" -o json >"$private_dir/pod.json" || return 1
  claim="$(jq -er '.spec.volumes[] | select(.name == "matching-baseline") | .persistentVolumeClaim.claimName' "$private_dir/pod.json")" || return 1
  kns get pvc "$claim" -o json >"$private_dir/pvc.json" || return 1
  volume="$(jq -er '.spec.volumeName' "$private_dir/pvc.json")" || return 1
  timeout --foreground --signal=TERM --kill-after=2s "$(bounded_operation_timeout_seconds 10)s" \
    kubectl --context "$context" get pv "$volume" --request-timeout=10s -o json >"$private_dir/pv.json" || return 1
  ruby "$script_dir/lib/matching-owner-observation.rb" owner \
    "$private_dir/pod.json" "$private_dir/pvc.json" "$private_dir/pv.json" \
    >"$evidence_dir/matching-recovery/$phase-owner.json"
}

replace_matching_owner() {
  local uid ready
  kns delete pod "$matching_owner" --wait=false >"$evidence_dir/matching-recovery/restart.log" || return 1
  while (( $(date +%s%3N) < matching_recovery_deadline_ms )); do
    kns get pods -l "statefulset.kubernetes.io/pod-name=$matching_owner" -o json |
      ruby "$script_dir/lib/matching-owner-observation.rb" interruption "$matching_original_uid" \
      >"$evidence_dir/matching-recovery/interruption.json" || return 1
    if jq -e '.oldOwnerInterrupted == true' "$evidence_dir/matching-recovery/interruption.json" >/dev/null; then
      break
    fi
    sleep 1
  done
  jq -e '.oldOwnerInterrupted == true' "$evidence_dir/matching-recovery/interruption.json" >/dev/null || return 1
  while (( $(date +%s%3N) < matching_recovery_deadline_ms )); do
    uid="$(kns get pod "$matching_owner" --ignore-not-found -o jsonpath='{.metadata.uid}')" || return 1
    ready="$(kns get pod "$matching_owner" --ignore-not-found -o jsonpath='{.status.conditions[?(@.type=="Ready")].status}')" || return 1
    [[ -n "$uid" && "$uid" != "$matching_original_uid" && "$ready" == True ]] && return 0
    sleep 1
  done
  return 1
}

wait_matching_replayed() {
  local input_offset metrics_path
  input_offset="$(jq -er '.offset' "$evidence_dir/kafka/matching-command-observation.json")" || return 1
  metrics_path="$(jq -er '.spec.containers[] | select(.name == "matching") | .env[] | select(.name == "MATCHING_METRICS_PATH") | .value' "$fix_state_dir/matching-owner/pod.json")" || return 1
  case "$metrics_path" in /var/run/simplematch/matching/runtime-metrics.json|/var/lib/simplematch/matching/runtime-metrics.json) ;; *) return 1 ;; esac
  while (( $(date +%s%3N) < matching_recovery_deadline_ms )); do
    kns exec "$matching_owner" -c matching -- cat "$metrics_path" >"$evidence_dir/matching-recovery/runtime-after.json" || return 1
    if jq -e --argjson offset "$input_offset" '
      .runtime_state == "READY" and .partition_state == "OPEN"
      and .admission.ownership_permitted == true and .admission.recovery_complete == true
      and .pending_inputs == 0 and .pending_publications == 0
      and (.highest_contiguous_completed_offset | type) == "number"
      and .highest_contiguous_completed_offset >= $offset
    ' "$evidence_dir/matching-recovery/runtime-after.json" >/dev/null; then
      return 0
    fi
    sleep 1
  done
  return 1
}

capture_matching_durable_state() {
  local phase="$1" selected_event="$2"
  kns exec -i "$postgres" -c postgres -- psql -U simplematch -d simplematch -At \
    -v ON_ERROR_STOP=1 -v account_id="$account_id" -v order_id="$order_id" \
    -v trading_day="$trading_day" -v event_id="$selected_event" -f - \
    <"$script_dir/sql/resting-buy-durable-state.sql" >"$evidence_dir/matching-recovery/durable-after-$phase.json"
}

run_matching_kafka_helper() {
  local main_class="$1" selected_command="$2" selected_offset="$3"
  local kubernetes_request_timeout_seconds
  kubernetes_request_timeout_seconds="$(matching_recovery_remaining_seconds)" || return 1
  kns exec "$kafka_observer_pod" -c observer -- java -cp '/app/lib/*' \
    "com.simplematch.tools.riskmatchinge2e.$main_class" \
    --bootstrap kafka:9092 --topic matching.events --partition "$partition" \
    --start-offset "$selected_offset" --command-id "$selected_command" --order-id "$order_id" \
    --timeout-seconds "$kubernetes_request_timeout_seconds" --evidence-dir /tmp/matching-recovery "${@:4}"
}

run_matching_business_recovery() {
  local original_timeout="$timeout_seconds" started_ms recovered_ms cancel_command cancel_event cancel_offset redelivery_offset
  matching_recovery_original_timeout_seconds="$timeout_seconds"
  matching_owner="matching-$partition"
  mkdir -p "$evidence_dir/matching-recovery"
  current_stage='capture the actual routed Matching owner'
  capture_matching_owner before || return 1
  jq -e '.ready == true' "$evidence_dir/matching-recovery/before-owner.json" >/dev/null || return 1
  matching_original_uid="$(jq -er '.podUid' "$evidence_dir/matching-recovery/before-owner.json")" || return 1
  started_ms="$(date +%s%3N)"
  matching_recovery_deadline_ms=$((started_ms + original_timeout * 1000))
  operation_deadline_epoch_ms="$matching_recovery_deadline_ms"
  kubernetes_request_timeout_seconds=10
  current_stage='replace Matching and prove replay catch-up of the original command'
  replace_matching_owner || die 'actual Matching owner replacement did not complete'
  capture_matching_owner after || return 1
  wait_matching_replayed || die 'Matching was not Ready with the original command replayed'
  recovered_ms="$(date +%s%3N)"
  capture_matching_durable_state replay "$event_id" || return 1

  current_stage='reopen from live observations and submit a new FIX cancel'
  timeout_seconds="$(matching_recovery_remaining_seconds)"
  open_gateway_from_live_observations "$evidence_dir/baseline/open-request.json" \
    "$evidence_dir/matching-recovery/gateway-before-open.json" "$evidence_dir/matching-recovery/gateway-open.json" ||
    die 'Gateway did not reopen from authentic live observations'
  capture_kafka_log_end_positions "$evidence_dir/matching-recovery/commands-before.json" "$evidence_dir/matching-recovery/events-before.json" || return 1
  printf '%s\n' "$matching_recovery_deadline_ms" >"$fix_state_dir/cancel-release"
  while [[ ! -s "$evidence_dir/matching-recovery/fix-cancel.json" ]]; do
    matching_recovery_remaining_seconds >/dev/null
    background_process_is_alive "$fix_submit_pid" || die 'post-recovery FIX cancel failed'
    sleep 1
  done
  while background_process_is_alive "$fix_submit_pid"; do
    matching_recovery_remaining_seconds >/dev/null
    sleep 1
  done
  wait_fix_submit_client || die 'post-recovery FIX client did not complete'
  kns exec -i "$postgres" -c postgres -- psql -U simplematch -d simplematch -At \
    -v ON_ERROR_STOP=1 -v account_id="$account_id" -v cl_ord_id="CAN-$cl_ord_id" -f - \
    <"$script_dir/sql/resting-buy-admission.sql" >"$evidence_dir/matching-recovery/risk-cancel.json" || return 1
  cancel_command="$(jq -er '.commandId' "$evidence_dir/matching-recovery/risk-cancel.json")" || return 1
  cancel_offset="$(jq -er --argjson partition "$partition" '.partitions[] | select(.partition == $partition) | .offset' "$evidence_dir/matching-recovery/events-before.json")" || return 1
  kns exec -i "$kafka_observer_pod" -c observer -- sh -c 'tee /tmp/cancel-commands-before.json >/dev/null' \
    <"$evidence_dir/matching-recovery/commands-before.json" || return 1
  run_matching_kafka_helper MatchingEventObservationMain "$cancel_command" "$cancel_offset" --commands-before /tmp/cancel-commands-before.json || return 1
  for name in matching-command-observation matching-event-observation; do
    kns exec "$kafka_observer_pod" -c observer -- cat "/tmp/matching-recovery/$name.json" >"$evidence_dir/matching-recovery/$name.json" || return 1
  done
  cancel_event="$(jq -er '.eventId' "$evidence_dir/matching-recovery/matching-event-observation.json")" || return 1
  cancel_offset="$(jq -er '.offset' "$evidence_dir/matching-recovery/matching-event-observation.json")" || return 1
  timeout_seconds="$(matching_recovery_remaining_seconds)"
  wait_consumers_through "$partition" "$cancel_offset" || return 1
  capture_matching_durable_state cancel "$cancel_event" || return 1

  current_stage='observe byte-identical Kafka redelivery and unchanged business effects'
  run_matching_kafka_helper MatchingEventRedeliveryMain "$cancel_command" "$cancel_offset" || return 1
  kns exec "$kafka_observer_pod" -c observer -- cat /tmp/matching-recovery/redelivery.json >"$evidence_dir/matching-recovery/redelivery.json" || return 1
  redelivery_offset="$(jq -er '.observedOffset' "$evidence_dir/matching-recovery/redelivery.json")" || return 1
  timeout_seconds="$(matching_recovery_remaining_seconds)"
  wait_consumers_through "$partition" "$redelivery_offset" || return 1
  kns exec -i "$postgres" -c postgres -- psql -U simplematch -d simplematch -At \
    -v ON_ERROR_STOP=1 -v partition="$partition" -f - \
    <"$script_dir/sql/matching-recovery-progress.sql" >"$evidence_dir/matching-recovery/consumer-progress.json" || return 1
  capture_matching_durable_state redelivery "$cancel_event" || return 1
  timeout_seconds="$(matching_recovery_remaining_seconds)"
  wait_gateway_live_open "$evidence_dir/matching-recovery/gateway-final.json" || die 'Gateway did not remain healthy and OPEN'
  ruby "$script_dir/lib/matching-owner-observation.rb" timing "$started_ms" "$recovered_ms" "$((original_timeout * 1000))" \
    >"$evidence_dir/matching-recovery/timing.json" || return 1
  ruby "$script_dir/lib/matching-recovery-verification.rb" "$evidence_dir" || return 1
  timeout_seconds="$original_timeout"
  kubernetes_request_timeout_seconds=""
  operation_deadline_epoch_ms=""
}
