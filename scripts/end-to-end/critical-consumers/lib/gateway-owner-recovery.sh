#!/usr/bin/env bash

# One opt-in Gateway restart, using the existing FIX client and durable SQL seams.
# Raw Kubernetes resources and FIX state stay in the caller's private mktemp directory.

gateway_recovery_remaining_seconds() {
  local remaining=$((gateway_recovery_deadline_ms - $(date +%s%3N)))
  (( remaining > 0 )) || die 'Gateway recovery exceeded its single deadline'
  printf '%s\n' "$(((remaining + 999) / 1000))"
}

wait_fix_submission_evidence() {
  local deadline=$(( $(date +%s) + timeout_seconds ))
  while (( $(date +%s) < deadline )); do
    [[ -s "$evidence_dir/fix/submit.json" ]] && return 0
    background_process_is_alive "$fix_submit_pid" || return 1
    sleep 1
  done
  return 1
}

capture_gateway_recovery_state() {
  local phase="$1" claim volume record_id
  local private_dir="$fix_state_dir/owner-resources"
  mkdir -p "$private_dir"
  kns get pod quickfix-gateway-0 -o json >"$private_dir/pod.json"
  kns get service quickfix-gateway-owner-0 -o json >"$private_dir/service.json"
  claim="$(jq -er '.spec.volumes[] | select(.name == "quickfix-data") | .persistentVolumeClaim.claimName' "$private_dir/pod.json")"
  kns get pvc "$claim" -o json >"$private_dir/pvc.json"
  volume="$(jq -er '.spec.volumeName' "$private_dir/pvc.json")"
  timeout "$(bounded_operation_timeout_seconds 10)" \
    kubectl --context "$context" get pv "$volume" --request-timeout=10s -o json >"$private_dir/pv.json"
  ruby "$script_dir/lib/gateway-recovery-verification.rb" owner \
    "$private_dir/pod.json" "$private_dir/service.json" "$private_dir/pvc.json" "$private_dir/pv.json" \
    >"$evidence_dir/recovery/$phase-owner.json"
  kns exec -i "$postgres" -c postgres -- psql -U simplematch -d simplematch -At \
    -v ON_ERROR_STOP=1 -f - <"$script_dir/sql/gateway-session-state.sql" \
    >"$evidence_dir/recovery/$phase-session.json"
  kns exec quickfix-gateway-0 -c quickfix-gateway -- \
    cat /var/lib/simplematch/quickfix-gateway/wal/inbound.wal |
    ruby "$script_dir/lib/gateway-recovery-verification.rb" wal "$account_id" "$cl_ord_id" \
      >"$evidence_dir/recovery/$phase-wal.json"
  record_id="$(jq -er '.records | if length == 1 then .[0].recordId else error("one original WAL record is required") end' "$evidence_dir/recovery/$phase-wal.json")"
  kns exec quickfix-gateway-0 -c quickfix-gateway -- \
    cat /var/lib/simplematch/quickfix-gateway/wal/inbound.wal.recovery |
    ruby "$script_dir/lib/gateway-recovery-verification.rb" journal "$record_id" \
      >"$evidence_dir/recovery/$phase-journal.json"
}

sample_gateway_recovery_owners() {
  while (( $(date +%s%3N) < gateway_recovery_deadline_ms )); do
    kns get pods -l app.kubernetes.io/name=quickfix-gateway -o json |
      ruby "$script_dir/lib/gateway-recovery-verification.rb" sample "$gateway_original_uid" || return 1
    sleep 1
  done
}

wait_gateway_replacement_ready() {
  local uid ready
  while (( $(date +%s%3N) < gateway_recovery_deadline_ms )); do
    uid="$(kns get pod quickfix-gateway-0 --ignore-not-found -o jsonpath='{.metadata.uid}')"
    ready="$(kns get pod quickfix-gateway-0 --ignore-not-found -o jsonpath='{.status.conditions[?(@.type=="Ready")].status}')"
    if [[ -n "$uid" && "$uid" != "$gateway_original_uid" && "$ready" == True ]]; then
      return 0
    fi
    sleep 1
  done
  return 1
}

capture_restored_gateway_readiness() {
  local private_dir="$fix_state_dir/restored-resources"
  mkdir -p "$private_dir" || return 1
  kns get pod quickfix-gateway-0 -o json >"$private_dir/pod.json" || return 1
  kns get statefulset quickfix-gateway -o json >"$private_dir/statefulset.json" || return 1
  start_port_forward pod/quickfix-gateway-0 8080 \
    "$evidence_dir/recovery/restored-management-port-forward.log" \
    gateway_port_forward_pid gateway_port || return 1
  curl --fail --connect-timeout 5 --max-time 15 -sS \
    "http://127.0.0.1:$gateway_port/readyz" >"$private_dir/health.json" || return 1
  ruby "$script_dir/lib/gateway-recovery-verification.rb" restoration \
    "$private_dir/pod.json" "$private_dir/statefulset.json" "$private_dir/health.json" \
    >"$evidence_dir/recovery/restoration.json"
}

run_gateway_owner_recovery() {
  local original_timeout="$timeout_seconds" retained_port="$fix_port" started_ms
  gateway_recovery_original_timeout_seconds="$timeout_seconds"
  mkdir -p "$evidence_dir/recovery"
  current_stage='capture original Gateway owner and durable FIX state'
  capture_gateway_recovery_state before
  gateway_original_uid="$(jq -er '.podUid' "$evidence_dir/recovery/before-owner.json")"
  started_ms="$(date +%s%3N)"
  gateway_recovery_deadline_ms=$((started_ms + original_timeout * 1000))
  operation_deadline_epoch_ms="$gateway_recovery_deadline_ms"
  kubernetes_request_timeout_seconds=10
  sample_gateway_recovery_owners >"$evidence_dir/recovery/owner-samples.jsonl" &
  gateway_owner_sampler_pid="$!"
  current_stage='replace the same logical Gateway owner'
  kns delete pod quickfix-gateway-0 --wait=false >"$evidence_dir/recovery/restart.log"
  wait_gateway_replacement_ready || die 'same-owner Gateway replacement did not become Ready'

  current_stage='reconnect retained FIX client and reopen through live observations'
  timeout_seconds="$(gateway_recovery_remaining_seconds)"
  start_fix_port_forward "$retained_port"
  start_gateway_port_forward
  timeout_seconds="$(gateway_recovery_remaining_seconds)"
  open_gateway_from_live_observations "$evidence_dir/baseline/open-request.json" \
    "$evidence_dir/recovery/gateway-before-open.json" "$evidence_dir/recovery/gateway-open.json" ||
    die 'recovered Gateway did not accept authentic operator open'
  printf '%s\n' "$gateway_recovery_deadline_ms" >"$fix_state_dir/recovery-release"
  current_stage='verify actual reconnect, FIX resend and original order retry'
  while [[ ! -s "$evidence_dir/recovery/protocol.json" ]]; do
    gateway_recovery_remaining_seconds >/dev/null
    background_process_is_alive "$fix_submit_pid" || die 'retained FIX recovery client failed'
    sleep 1
  done
  while background_process_is_alive "$fix_submit_pid"; do
    gateway_recovery_remaining_seconds >/dev/null
    sleep 1
  done
  wait_fix_submit_client || die 'retained FIX recovery client did not complete'

  current_stage='verify singular Risk admission and unchanged business effects after retry'
  kns exec -i "$postgres" -c postgres -- psql -U simplematch -d simplematch -At \
    -v ON_ERROR_STOP=1 -v account_id="$account_id" -v cl_ord_id="$cl_ord_id" -f - \
    <"$script_dir/sql/resting-buy-admission.sql" >"$evidence_dir/recovery/risk-after.json"
  kns exec -i "$postgres" -c postgres -- psql -U simplematch -d simplematch -At \
    -v ON_ERROR_STOP=1 -v account_id="$account_id" -v order_id="$order_id" \
    -v trading_day="$trading_day" -v event_id="$event_id" -f - \
    <"$script_dir/sql/resting-buy-durable-state.sql" >"$evidence_dir/recovery/durable-after.json"
  capture_gateway_recovery_state after
  timeout_seconds="$(gateway_recovery_remaining_seconds)"
  wait_gateway_live_open "$evidence_dir/recovery/gateway-final.json" || die 'recovered Gateway did not remain OPEN'
  ruby "$script_dir/lib/gateway-recovery-verification.rb" timing "$started_ms" "$((original_timeout * 1000))" \
    >"$evidence_dir/recovery/timing.json"
  background_process_is_alive "$gateway_owner_sampler_pid" || die 'Gateway owner observation ended unexpectedly'
  stop_background_process "$gateway_owner_sampler_pid"
  gateway_owner_sampler_pid=""
  ruby "$script_dir/lib/gateway-recovery-verification.rb" verify "$evidence_dir"
  timeout_seconds="$original_timeout"
  kubernetes_request_timeout_seconds=""
  operation_deadline_epoch_ms=""
}
