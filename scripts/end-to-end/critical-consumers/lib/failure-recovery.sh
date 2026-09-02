#!/usr/bin/env bash

# Failure injection, recovery verification, diagnostics, and environment restoration.

fix_submission_outcome_is_unknown() {
  local submission="$1"
  [[ -s "$submission" ]] || return 1
  jq -e '
    ((.execId // "") | startswith("UN-"))
    or ((.text // "") | startswith("SYSTEM_ERROR: order outcome is pending confirmation"))
  ' "$submission" >/dev/null 2>&1
}

require_fix_submission_accepted() {
  local submission="$1"
  if [[ ! -s "$submission" ]]; then
    die 'FIX submission evidence is missing'
    return 1
  fi

  if fix_submission_outcome_is_unknown "$submission"; then
    die 'FIX Risk admission outcome remained UNKNOWN'
    return 1
  fi

  local exec_type ord_status text
  exec_type="$(jq -r '.execType // empty' "$submission")"
  ord_status="$(jq -r '.ordStatus // empty' "$submission")"
  text="$(jq -r '.text // ""' "$submission")"

  if [[ "$exec_type" == A && "$ord_status" == A ]]; then
    return 0
  fi

  if [[ "$exec_type" == 8 || "$ord_status" == 8 ]]; then
    die "FIX order was rejected before durable Risk admission: ExecType=$exec_type OrdStatus=$ord_status Text=$text"
    return 1
  fi

  die "FIX admission response was unexpected: ExecType=$exec_type OrdStatus=$ord_status Text=$text"
  return 1
}

capture_risk_admission() {
  local destination="$1"
  require_fix_submission_accepted "$evidence_dir/fix/submit.json"

  local postgres
  postgres="$(postgres_pod)"
  [[ -n "$postgres" ]] || die 'cannot resolve PostgreSQL Pod for Risk admission'
  local admission
  admission="$(
    kns exec "$postgres" -c postgres -- psql -U simplematch -d simplematch -At \
      -v ON_ERROR_STOP=1 -c "
        SELECT json_build_object(
          'commandId', command_id::text,
          'orderId', order_id::text,
          'accountId', account_id::text,
          'clOrdId', cl_ord_id,
          'state', state,
          'routingPartition', routing_partition
        )::text
        FROM risk_service.admission_journal
        WHERE account_id = '$account_id'
          AND cl_ord_id = '$cl_ord_id'
        ORDER BY id DESC
        LIMIT 1;
      "
  )"
  [[ -n "$admission" ]] || die 'FIX order has no Risk admission row'
  printf '%s\n' "$admission" | jq . >"$destination"
  jq -e '.state == "ACCEPTED" and (.routingPartition >= 0 and .routingPartition <= 14)' \
    "$destination" >/dev/null ||
    die 'FIX submission did not produce an accepted routed Risk admission'
}

require_matching_command_held() {
  local before="$1"
  local partition="$2"
  local after="$evidence_dir/submission/matching-commands-held.json"
  capture_topic_offsets matching.commands "$after" || die 'cannot recapture matching.commands offsets'
  local before_offset after_offset
  before_offset="$(offset_for_partition "$before" "$partition")"
  after_offset="$(offset_for_partition "$after" "$partition")"
  [[ "$after_offset" == "$before_offset" ]] ||
    die 'matching.commands moved while the Risk outbox connector was paused'
}

release_matching_command() {
  local before="$1"
  local partition="$2"
  local before_offset
  before_offset="$(offset_for_partition "$before" "$partition")"
  resume_risk_outbox
  local snapshot="$evidence_dir/submission/matching-commands-published.json"
  for _ in $(seq 1 "$timeout_seconds"); do
    capture_topic_offsets matching.commands "$snapshot" || {
      sleep 1
      continue
    }
    local after_offset
    after_offset="$(offset_for_partition "$snapshot" "$partition")"
    if (( after_offset > before_offset )); then
      (( after_offset == before_offset + 1 )) ||
        die 'more than one command reached the target partition during release'
      pause_risk_outbox
      return 0
    fi
    sleep 1
  done
  die 'accepted Risk command did not reach matching.commands after outbox resume'
}

create_observer_pod() {
  kns get pod "$observer_pod" >/dev/null 2>&1 &&
    die "observer Pod already exists: $observer_pod"
  kns create -f "$observer_manifest" >/dev/null
  observer_created=true
  kns wait --for=condition=Ready "pod/$observer_pod" --timeout="${timeout_seconds}s" >/dev/null
}

run_event_observer() {
  local partition="$1"
  local start_offset="$2"
  local command_id="$3"
  local order_id="$4"
  kns exec "$observer_pod" -c observer -- \
    java -cp '/app/lib/*' com.simplematch.tools.riskmatchinge2e.MatchingEventObservationMain \
      --bootstrap kafka:9092 \
      --topic matching.events \
      --partition "$partition" \
      --start-offset "$start_offset" \
      --command-id "$command_id" \
      --order-id "$order_id" \
      --timeout-seconds "$timeout_seconds" \
      --evidence-dir /tmp/evidence
  kns cp -c observer "$observer_pod:/tmp/evidence/matching-event-observation.json" \
    "$evidence_dir/outage/matching-event-observation.json" >/dev/null
  kns cp -c observer "$observer_pod:/tmp/evidence/matching-event-observer-verdict.json" \
    "$evidence_dir/outage/matching-event-observer-verdict.json" >/dev/null
  jq -e '.status == "PASS"' "$evidence_dir/outage/matching-event-observer-verdict.json" >/dev/null ||
    die 'in-cluster Matching Event observer did not report PASS'
}

wait_consumers_through() {
  local partition="$1"
  local offset="$2"
  for _ in $(seq 1 "$timeout_seconds"); do
    local postgres
    postgres="$(postgres_pod)"
    if [[ -n "$postgres" ]]; then
      local values
      values="$(
        kns exec "$postgres" -c postgres -- psql -U simplematch -d simplematch -At \
          -v ON_ERROR_STOP=1 -c "
            SELECT
              COALESCE((SELECT last_processed_offset
                FROM persistence.matching_consumer_progress
                WHERE consumer_name = 'persistence-matching-events'
                  AND partition_id = $partition), -1)
              || '|' ||
              COALESCE((SELECT last_processed_offset
                FROM account_service.matching_event_consumer_progress
                WHERE consumer_name = 'account-final-matching-events'
                  AND partition_id = $partition), -1)
              || '|' ||
              COALESCE((SELECT last_processed_offset
                FROM quickfix_gateway.matching_consumer_progress
                WHERE consumer_name = 'quickfix-final-matching-events'
                  AND partition_id = $partition), -1);
          " 2>/dev/null || true
      )"
      IFS='|' read -r persistence_offset account_offset quickfix_offset <<<"$values"
      if [[ "$persistence_offset" =~ ^[0-9]+$ \
            && "$account_offset" =~ ^[0-9]+$ \
            && "$quickfix_offset" =~ ^[0-9]+$ ]] \
          && (( persistence_offset >= offset \
                && account_offset >= offset \
                && quickfix_offset >= offset )); then
        return 0
      fi
    fi
    sleep 1
  done
  die "critical consumers did not process matching.events partition $partition offset $offset"
}

require_exact_event_once() {
  local event_id="$1"
  local postgres
  postgres="$(postgres_pod)"
  [[ -n "$postgres" ]] || die 'cannot resolve PostgreSQL Pod for inbox verification'
  local counts
  counts="$(
    kns exec "$postgres" -c postgres -- psql -U simplematch -d simplematch -At \
      -v ON_ERROR_STOP=1 -c "
        SELECT
          (SELECT COUNT(*) FROM persistence.matching_event_inbox
           WHERE consumer_name = 'persistence-matching-events'
             AND event_id = decode('$event_id', 'hex'))
          || '|' ||
          (SELECT COUNT(*) FROM account_service.matching_event_inbox
           WHERE consumer_name = 'account-final-matching-events'
             AND event_id = decode('$event_id', 'hex'))
          || '|' ||
          (SELECT COUNT(*) FROM quickfix_gateway.matching_event_inbox
           WHERE consumer_name = 'quickfix-final-matching-events'
             AND event_id = decode('$event_id', 'hex'));
      "
  )"
  [[ "$counts" == '1|1|1' ]] ||
    die "exact Matching Event was not processed once by all critical consumers: $counts"
}

capture_exact_event_counts() {
  local event_id="$1"
  local destination="$2"
  local postgres
  postgres="$(postgres_pod)"
  [[ -n "$postgres" ]] || return 1

  local counts
  counts="$(
    kns exec "$postgres" -c postgres -- psql -U simplematch -d simplematch -At \
      -v ON_ERROR_STOP=1 -c "
        SELECT json_build_object(
          'persistence', (SELECT COUNT(*) FROM persistence.matching_event_inbox
            WHERE consumer_name = 'persistence-matching-events'
              AND event_id = decode('$event_id', 'hex')),
          'account', (SELECT COUNT(*) FROM account_service.matching_event_inbox
            WHERE consumer_name = 'account-final-matching-events'
              AND event_id = decode('$event_id', 'hex')),
          'quickfix', (SELECT COUNT(*) FROM quickfix_gateway.matching_event_inbox
            WHERE consumer_name = 'quickfix-final-matching-events'
              AND event_id = decode('$event_id', 'hex')),
          'marketData', (SELECT COUNT(*) FROM market_data_projection.matching_event_inbox
            WHERE event_id = decode('$event_id', 'hex'))
        )::text;
      "
  )" || return 1
  [[ -n "$counts" ]] || return 1
  printf '%s\n' "$counts" | jq -e . >"$destination"
}

require_exact_event_once_with_market_data() {
  local event_id="$1"
  local destination="${2:-$evidence_dir/submission/exact-inbox-counts.json}"
  capture_exact_event_counts "$event_id" "$destination" ||
    die 'cannot capture exact Matching Event inbox counts'
  jq -e '
    .persistence == 1
    and .account == 1
    and .quickfix == 1
    and .marketData == 1
  ' "$destination" >/dev/null ||
    die 'exact Matching Event was not processed once by all critical consumers'
}

capture_market_data_progress() {
  local partition="$1"
  local destination="$2"
  local postgres
  postgres="$(postgres_pod)"
  [[ -n "$postgres" ]] || return 1

  local progress
  progress="$(
    kns exec "$postgres" -c postgres -- psql -U simplematch -d simplematch -At \
      -v ON_ERROR_STOP=1 -c "
        SELECT json_build_object(
          'partition', $partition,
          'lastProcessedOffset', COALESCE(last_processed_offset, -1),
          'recoveryState', COALESCE(recovery_state, 'MISSING')
        )::text
        FROM market_data_projection.partition_projection_progress
        WHERE partition_id = $partition;
      "
  )" || return 1
  [[ -n "$progress" ]] || return 1
  printf '%s\n' "$progress" | jq -e . >"$destination"
}

wait_market_data_through() {
  local partition="$1"
  local offset="$2"
  local destination="$3"
  for _ in $(seq 1 "$timeout_seconds"); do
    if capture_market_data_progress "$partition" "$destination" \
        && jq -e --argjson offset "$offset" '
          .partition >= 0
          and .lastProcessedOffset >= $offset
          and .recoveryState == "READY"
        ' "$destination" >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
  done
  return 1
}

capture_fix_intent() {
  local destination="$1"
  local postgres
  postgres="$(postgres_pod)"
  [[ -n "$postgres" ]] || return 1
  local intent
  intent="$(
    kns exec "$postgres" -c postgres -- psql -U simplematch -d simplematch -At \
      -v ON_ERROR_STOP=1 -c "
        SELECT json_build_object(
          'deliveryId', encode(delivery_id, 'hex'),
          'eventId', encode(event_id, 'hex'),
          'clientOrderId', client_order_id,
          'execId', exec_id,
          'execType', exec_type,
          'ordStatus', ord_status,
          'status', status,
          'sourcePartition', source_partition,
          'sourceOffset', source_offset
        )::text
        FROM quickfix_gateway.fix_delivery_intents
        WHERE client_order_id = '$cl_ord_id'
        ORDER BY source_offset DESC, delivery_index DESC
        LIMIT 1;
      " 2>/dev/null
  )" || return 1
  [[ -n "$intent" ]] || return 1
  printf '%s\n' "$intent" | jq . >"$destination"
}

wait_fix_intent_status() {
  local expected="$1"
  local destination="$2"
  for _ in $(seq 1 "$timeout_seconds"); do
    if capture_fix_intent "$destination" \
        && [[ "$(jq -r '.status' "$destination")" == "$expected" ]]; then
      return 0
    fi
    sleep 1
  done
  die "QuickFIX delivery intent did not reach status $expected"
}

quickfix_pod_uid() {
  kns get pod quickfix-gateway-0 -o jsonpath='{.metadata.uid}'
}

statefulset_revision_converged() {
  local name="$1"
  local replicas="$2"
  local revision
  revision="$(kns get "statefulset/$name" -o jsonpath='{.status.updateRevision}')" || return 1
  [[ -n "$revision" ]] || return 1
  kns get pods -l "app.kubernetes.io/name=$name" -o json |
    jq -e --arg revision "$revision" --argjson replicas "$replicas" '
      (.items | length) == $replicas
      and all(.items[];
        .metadata.labels["controller-revision-hash"] == $revision
        and any(.status.conditions[]?; .type == "Ready" and .status == "True"))
    ' >/dev/null
}

collect_diagnostics() {
  kns get pods -o wide >"$evidence_dir/diagnostics/pods.txt" 2>&1 || true
  kns get deployments,statefulsets,pods,pvc >"$evidence_dir/diagnostics/workloads.txt" 2>&1 || true
  kns get statefulset quickfix-gateway -o json \
    >"$evidence_dir/diagnostics/quickfix-gateway-statefulset.json" 2>&1 || true
  kns get pod quickfix-gateway-0 -o json \
    >"$evidence_dir/diagnostics/quickfix-gateway-pod.json" 2>&1 || true
  kns logs quickfix-gateway-0 -c quickfix-gateway --previous --tail=300 \
    >"$evidence_dir/diagnostics/quickfix-gateway-previous.log" 2>&1 || true
  local selector
  for selector in account-service persistence quickfix-gateway risk-service; do
    kns logs -l "app.kubernetes.io/name=$selector" \
      --all-containers=true --prefix=true --tail=300 \
      >"$evidence_dir/diagnostics/${selector}.log" 2>&1 || true
  done
  kns logs -l app.kubernetes.io/name=matching \
    --all-containers=true --prefix=true --tail=300 \
    >"$evidence_dir/diagnostics/matching.log" 2>&1 || true
}

restore_workloads() {
  set +e
  [[ -z "$original_postgres_replicas" ]] ||
    kns scale statefulset/postgres --replicas="$original_postgres_replicas" >/dev/null 2>&1
  [[ -z "$original_account_replicas" ]] ||
    kns scale deployment/account-service --replicas="$original_account_replicas" >/dev/null 2>&1
  [[ -z "$original_persistence_replicas" ]] ||
    kns scale deployment/persistence --replicas="$original_persistence_replicas" >/dev/null 2>&1
  [[ -z "$original_quickfix_replicas" ]] ||
    kns scale statefulset/quickfix-gateway --replicas="$original_quickfix_replicas" >/dev/null 2>&1
  [[ -z "$original_matching_replicas" ]] ||
    kns scale statefulset/matching --replicas="$original_matching_replicas" >/dev/null 2>&1

  [[ -z "$original_postgres_replicas" || "$original_postgres_replicas" -eq 0 ]] ||
    kns rollout status statefulset/postgres --timeout="${timeout_seconds}s" >/dev/null 2>&1 || restoration_failed=true
  [[ -z "$original_account_replicas" || "$original_account_replicas" -eq 0 ]] ||
    kns rollout status deployment/account-service --timeout="${timeout_seconds}s" >/dev/null 2>&1 || restoration_failed=true
  [[ -z "$original_persistence_replicas" || "$original_persistence_replicas" -eq 0 ]] ||
    kns rollout status deployment/persistence --timeout="${timeout_seconds}s" >/dev/null 2>&1 || restoration_failed=true
  if [[ -n "$original_quickfix_replicas" && "$original_quickfix_replicas" -ne 0 ]]; then
    if ! kns rollout status statefulset/quickfix-gateway \
        --timeout="${timeout_seconds}s" >/dev/null 2>&1; then
      restoration_failed=true
    elif ! statefulset_revision_converged quickfix-gateway "$original_quickfix_replicas"; then
      restoration_failed=true
    fi
  fi
  [[ -z "$original_matching_replicas" || "$original_matching_replicas" -eq 0 ]] ||
    kns rollout status statefulset/matching --timeout="${timeout_seconds}s" >/dev/null 2>&1 || restoration_failed=true
  set -e
}
