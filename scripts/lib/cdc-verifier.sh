#!/usr/bin/env bash

# Deep CDC verification Module.
#
# Interface:
#   1. cdc_capture_topic_end_offsets <topic> <snapshot-file>
#   2. cdc_wait_for_connector_state <connector-name> <expected-state> [timeout-seconds]
#   3. cdc_read_outbox_probe <schema> <aggregate-type> <aggregate-id> <probe-file>
#   4. cdc_assert_same_probe <expected-probe> <observed-probe>
#   5. cdc_assert_probe_publication <probe-file> <baseline-snapshot>
#
# The CdcProbeIdentity is a test-side JSON document produced by cdc_read_outbox_probe. It carries
# the durable outbox identity and immutable publication contract so scenario callers do not rebuild
# Kafka assertions field-by-field. Its payload_hex field is sensitive implementation state used only
# for byte-exact comparison and must never be printed in diagnostics.
#
# Ordering and invariants:
#   - Capture the Kafka baseline before committing or publishing the event under test.
#   - Read the committed outbox row by stable business/aggregate identity through CDC_OUTBOX_EXEC.
#   - The topic partition set must remain unchanged between baseline and verification.
#   - Event identity is the exact Debezium EventRouter `id` header; Kafka key text never locates it.
#   - A supplied outbox partition is authoritative; a NULL partition is discovered, never guessed.
#   - RUNNING connector state is prerequisite evidence only; publication success is separate.
#
# Adapters at the external seams:
#   - CDC_KAFKA_EXEC executes Kafka CLI commands.
#   - CDC_OUTBOX_EXEC receives one SQL string and returns one tab-separated outbox row.
#   - CDC_CONNECT_STATUS_EXEC receives one connector name and returns its Connect status JSON.
# The Compose harness and this Module's fakes are two concrete Adapters today; #156 may provide
# Kubernetes-backed Adapters without changing this Interface or copying its observation logic.
#
# Error modes and performance:
#   - CDC_VERIFIER_TIMEOUT_SECONDS bounds polling and CDC_VERIFIER_SCAN_TIMEOUT_MS bounds each scan.
#   - Kafka/Connect/outbox failures, topology drift, missing rows, and exact-record mismatches fail
#     closed. Diagnostics identify event/topic/location where known, but never print raw payloads.

CDC_KAFKA_BOOTSTRAP="${CDC_KAFKA_BOOTSTRAP:-kafka:29092}"
CDC_VERIFIER_TIMEOUT_SECONDS="${CDC_VERIFIER_TIMEOUT_SECONDS:-30}"
CDC_VERIFIER_POLL_INTERVAL_SECONDS="${CDC_VERIFIER_POLL_INTERVAL_SECONDS:-1}"
CDC_VERIFIER_SCAN_TIMEOUT_MS="${CDC_VERIFIER_SCAN_TIMEOUT_MS:-2000}"
CDC_VERIFIER_HEADER_SEPARATOR="${CDC_VERIFIER_HEADER_SEPARATOR:-__SIMPLEMATCH_CDC_HEADER__}"

_cdc_fail() {
  printf 'CDC verifier: %s\n' "$*" >&2
  return 1
}

_cdc_require_kafka_exec() {
  declare -p CDC_KAFKA_EXEC >/dev/null 2>&1 \
    || _cdc_fail 'CDC_KAFKA_EXEC array is not configured'
  [[ ${#CDC_KAFKA_EXEC[@]} -gt 0 ]] || _cdc_fail 'CDC_KAFKA_EXEC array is empty'
}

_cdc_require_outbox_exec() {
  declare -p CDC_OUTBOX_EXEC >/dev/null 2>&1 \
    || _cdc_fail 'CDC_OUTBOX_EXEC array is not configured'
  [[ ${#CDC_OUTBOX_EXEC[@]} -gt 0 ]] || _cdc_fail 'CDC_OUTBOX_EXEC array is empty'
}

_cdc_require_connect_status_exec() {
  declare -p CDC_CONNECT_STATUS_EXEC >/dev/null 2>&1 \
    || _cdc_fail 'CDC_CONNECT_STATUS_EXEC array is not configured'
  [[ ${#CDC_CONNECT_STATUS_EXEC[@]} -gt 0 ]] \
    || _cdc_fail 'CDC_CONNECT_STATUS_EXEC array is empty'
}

_cdc_kafka() {
  _cdc_require_kafka_exec || return 1
  "${CDC_KAFKA_EXEC[@]}" "$@"
}

_cdc_outbox() {
  _cdc_require_outbox_exec || return 1
  "${CDC_OUTBOX_EXEC[@]}" "$@"
}

_cdc_connect_status() {
  _cdc_require_connect_status_exec || return 1
  "${CDC_CONNECT_STATUS_EXEC[@]}" "$@"
}

_cdc_is_uint() {
  [[ "$1" =~ ^[0-9]+$ ]]
}

_cdc_is_uuid() {
  [[ "$1" =~ ^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$ ]]
}

_cdc_payload_sha256() {
  local payload_hex="$1"
  [[ -n "$payload_hex" && "$payload_hex" =~ ^([0-9a-fA-F]{2})+$ ]] \
    || _cdc_fail 'payload is missing or is not hexadecimal' \
    || return 1
  command -v xxd >/dev/null 2>&1 || _cdc_fail 'xxd is required for payload hashing' || return 1
  command -v sha256sum >/dev/null 2>&1 \
    || _cdc_fail 'sha256sum is required for payload hashing' \
    || return 1
  printf '%s' "$payload_hex" | xxd -r -p | sha256sum | awk '{print $1}'
}

_cdc_probe_field() {
  local probe="$1" field="$2"
  jq -r --arg field "$field" '.[$field] // "null"' "$probe"
}

_cdc_validate_probe() {
  local probe="$1" event_id payload_hex payload_sha actual_sha
  [[ -s "$probe" ]] || _cdc_fail "probe is missing or empty: $probe" || return 1
  jq -e '
      (.event_id | type == "string" and length > 0) and
      (.business_identity | type == "string" and length > 0) and
      (.message_key | type == "string" and length > 0) and
      (.topic | type == "string" and length > 0) and
      (.payload_hex | type == "string" and length > 0) and
      (.payload_sha256 | type == "string" and test("^[0-9a-f]{64}$")) and
      (.created_at_unix_ms | type == "number" and . >= 0) and
      (.headers_json | type == "string" and length > 0) and
      ((.explicit_partition == null) or (.explicit_partition | type == "number" and . >= 0))
    ' "$probe" >/dev/null \
    || _cdc_fail "probe contract is invalid: $probe" \
    || return 1

  event_id="$(_cdc_probe_field "$probe" event_id)"
  _cdc_is_uuid "$event_id" || _cdc_fail "probe event identity is not a UUID: $event_id" || return 1
  payload_hex="$(_cdc_probe_field "$probe" payload_hex)"
  payload_sha="$(_cdc_probe_field "$probe" payload_sha256)"
  actual_sha="$(_cdc_payload_sha256 "$payload_hex")" || return 1
  [[ "$payload_sha" == "$actual_sha" ]] \
    || _cdc_fail "probe payload digest does not match its sensitive bytes for event $event_id" \
    || return 1
}

cdc_capture_topic_end_offsets() {
  local topic="$1" output="$2" raw line_topic partition offset
  [[ -n "$topic" && -n "$output" ]] \
    || _cdc_fail 'topic and output path are required' \
    || return 1

  raw="$(_cdc_kafka /opt/kafka/bin/kafka-get-offsets.sh \
    --bootstrap-server "$CDC_KAFKA_BOOTSTRAP" \
    --topic "$topic" \
    --time -1)" || return 1

  : >"$output"
  while IFS=: read -r line_topic partition offset; do
    [[ -n "$line_topic" ]] || continue
    [[ "$line_topic" == "$topic" ]] \
      || _cdc_fail "unexpected topic in offset snapshot: $line_topic" \
      || return 1
    _cdc_is_uint "$partition" \
      || _cdc_fail "invalid partition in offset snapshot: $partition" \
      || return 1
    _cdc_is_uint "$offset" \
      || _cdc_fail "invalid end offset in snapshot: $offset" \
      || return 1
    printf '%s\t%s\n' "$partition" "$offset" >>"$output"
  done <<<"$raw"

  [[ -s "$output" ]] \
    || _cdc_fail "topic $topic returned no partition offsets" \
    || return 1
  sort -n -k1,1 -o "$output" "$output"
}

cdc_wait_for_connector_state() {
  local connector_name="$1" expected_state="$2"
  local timeout="${3:-$CDC_VERIFIER_TIMEOUT_SECONDS}" deadline now status observed
  [[ -n "$connector_name" && -n "$expected_state" ]] \
    || _cdc_fail 'connector name and expected state are required' \
    || return 1
  _cdc_is_uint "$timeout" \
    || _cdc_fail "connector timeout must be an integer number of seconds: $timeout" \
    || return 1

  deadline=$(( $(date +%s) + timeout ))
  while :; do
    status="$(_cdc_connect_status "$connector_name" 2>/dev/null || true)"
    if jq -e --arg expected "$expected_state" '
        .connector.state == $expected and
        (.tasks | length) > 0 and
        ([.tasks[].state] | all(. == $expected))
      ' >/dev/null 2>&1 <<<"$status"; then
      return 0
    fi
    now="$(date +%s)"
    if (( now >= deadline )); then
      observed="$(jq -r '[.connector.state // "missing", (.tasks[]?.state // "missing")] | join(",")' \
        <<<"$status" 2>/dev/null || printf '%s' 'unavailable')"
      _cdc_fail \
        "connector $connector_name expected state $expected_state but observed [$observed] before timeout"
      return 1
    fi
    sleep "$CDC_VERIFIER_POLL_INTERVAL_SECONDS"
  done
}

cdc_read_outbox_probe() {
  local schema="$1" aggregate_type="$2" aggregate_id="$3" output="$4" sql raw row_count
  local event_id topic message_key partition payload_hex timestamp_ms headers_json
  local observed_aggregate_type observed_aggregate_id payload_sha reservation_id='' account_id=''
  local partition_json='null'

  [[ "$schema" =~ ^[a-z][a-z0-9_]*$ ]] \
    || _cdc_fail "invalid outbox schema name: $schema" \
    || return 1
  [[ "$aggregate_type" =~ ^[A-Za-z][A-Za-z0-9_.-]*$ ]] \
    || _cdc_fail "invalid outbox aggregate type: $aggregate_type" \
    || return 1
  [[ "$aggregate_id" =~ ^[A-Za-z0-9][A-Za-z0-9_.:-]*$ ]] \
    || _cdc_fail "invalid outbox business identity for $aggregate_type" \
    || return 1
  [[ -n "$output" ]] || _cdc_fail 'probe output path is required' || return 1

  sql="SELECT event_id::text, topic, message_key, COALESCE(kafka_partition_id::text, 'NULL'), encode(payload, 'hex'), round(extract(epoch from created_at) * 1000)::bigint, headers_json, aggregate_type, aggregate_id FROM ${schema}.outbox WHERE aggregate_type = '${aggregate_type}' AND aggregate_id = '${aggregate_id}'"
  raw="$(_cdc_outbox "$sql")" || {
    _cdc_fail \
      "failed to read durable ${aggregate_type} outbox event for business identity $aggregate_id from ${schema}.outbox"
    return 1
  }
  row_count="$(printf '%s\n' "$raw" | awk 'NF { count++ } END { print count + 0 }')"
  [[ "$row_count" == 1 ]] \
    || _cdc_fail \
      "durable ${aggregate_type} outbox event for business identity $aggregate_id in ${schema}.outbox: expected exactly one row, observed $row_count" \
    || return 1

  IFS=$'\t' read -r event_id topic message_key partition payload_hex timestamp_ms headers_json \
    observed_aggregate_type observed_aggregate_id <<<"$raw"
  _cdc_is_uuid "$event_id" \
    || _cdc_fail \
      "durable ${aggregate_type} outbox event for business identity $aggregate_id has invalid event identity" \
    || return 1
  [[ "$observed_aggregate_type" == "$aggregate_type" && "$observed_aggregate_id" == "$aggregate_id" ]] \
    || _cdc_fail \
      "outbox business identity mismatch for event $event_id: expected ${aggregate_type}/$aggregate_id observed ${observed_aggregate_type}/${observed_aggregate_id}" \
    || return 1
  [[ -n "$topic" && -n "$message_key" && -n "$headers_json" ]] \
    || _cdc_fail "outbox event $event_id has an incomplete publication contract" \
    || return 1
  _cdc_is_uint "$timestamp_ms" \
    || _cdc_fail "outbox event $event_id has invalid created_at timestamp" \
    || return 1
  if [[ "$partition" != NULL ]]; then
    _cdc_is_uint "$partition" \
      || _cdc_fail "outbox event $event_id has invalid explicit partition" \
      || return 1
    partition_json="$partition"
  fi
  payload_sha="$(_cdc_payload_sha256 "$payload_hex")" || return 1

  if [[ "$schema" == account_service ]]; then
    [[ "$aggregate_type" == account_reservation ]] \
      || _cdc_fail \
        "Account outbox event $event_id must reference account_reservation, observed $aggregate_type" \
      || return 1
    reservation_id="$aggregate_id"
    account_id="$message_key"
  fi

  jq -n \
    --arg event_id "$event_id" \
    --arg business_identity "$aggregate_id" \
    --arg reservation_id "$reservation_id" \
    --arg account_id "$account_id" \
    --arg message_key "$message_key" \
    --arg topic "$topic" \
    --arg payload_hex "${payload_hex,,}" \
    --arg payload_sha256 "$payload_sha" \
    --argjson created_at_unix_ms "$timestamp_ms" \
    --arg headers_json "$headers_json" \
    --arg aggregate_type "$aggregate_type" \
    --argjson explicit_partition "$partition_json" \
    '{
      event_id: $event_id,
      business_identity: $business_identity,
      reservation_id: (if $reservation_id == "" then null else $reservation_id end),
      account_id: (if $account_id == "" then null else $account_id end),
      message_key: $message_key,
      topic: $topic,
      payload_hex: $payload_hex,
      payload_sha256: $payload_sha256,
      created_at_unix_ms: $created_at_unix_ms,
      headers_json: $headers_json,
      aggregate_type: $aggregate_type,
      explicit_partition: $explicit_partition
    }' >"$output"
  _cdc_validate_probe "$output"
}

cdc_assert_same_probe() {
  local expected_probe="$1" observed_probe="$2" event_id field expected observed
  local expected_hex observed_hex expected_sha observed_sha expected_bytes observed_bytes
  _cdc_validate_probe "$expected_probe" || return 1
  _cdc_validate_probe "$observed_probe" || return 1
  event_id="$(_cdc_probe_field "$expected_probe" event_id)"

  for field in event_id business_identity reservation_id account_id message_key topic \
      created_at_unix_ms headers_json aggregate_type explicit_partition; do
    expected="$(_cdc_probe_field "$expected_probe" "$field")"
    observed="$(_cdc_probe_field "$observed_probe" "$field")"
    if [[ "$expected" != "$observed" ]]; then
      if [[ "$field" == headers_json ]]; then
        _cdc_fail "durable outbox event $event_id changed expected headers after recovery"
      else
        _cdc_fail \
          "durable outbox event $event_id changed $field: expected '$expected' observed '$observed'"
      fi
      return 1
    fi
  done

  expected_hex="$(_cdc_probe_field "$expected_probe" payload_hex)"
  observed_hex="$(_cdc_probe_field "$observed_probe" payload_hex)"
  if [[ "$expected_hex" != "$observed_hex" ]]; then
    expected_sha="$(_cdc_probe_field "$expected_probe" payload_sha256)"
    observed_sha="$(_cdc_probe_field "$observed_probe" payload_sha256)"
    expected_bytes=$(( ${#expected_hex} / 2 ))
    observed_bytes=$(( ${#observed_hex} / 2 ))
    _cdc_fail \
      "durable outbox event $event_id payload changed: expected_sha256=$expected_sha observed_sha256=$observed_sha expected_bytes=$expected_bytes observed_bytes=$observed_bytes"
    return 1
  fi
}

_cdc_snapshot_offset_for_partition() {
  local snapshot="$1" partition="$2" offset
  offset="$(awk -F '\t' -v partition="$partition" '$1 == partition { print $2; exit }' "$snapshot")"
  [[ -n "$offset" ]] \
    || _cdc_fail "partition $partition is absent from baseline snapshot $snapshot" \
    || return 1
  printf '%s\n' "$offset"
}

_cdc_partition_ids() {
  awk -F '\t' '{ print $1 }' "$1"
}

_cdc_assert_same_partition_set() {
  local topic="$1" baseline_snapshot="$2" current_snapshot="$3" event_id="$4"
  local baseline_partitions current_partitions
  baseline_partitions="$(_cdc_partition_ids "$baseline_snapshot")"
  current_partitions="$(_cdc_partition_ids "$current_snapshot")"
  [[ "$baseline_partitions" == "$current_partitions" ]] \
    || _cdc_fail \
      "event $event_id on $topic observed partition-set drift after baseline; baseline=[$(tr '\n' ',' <<<"$baseline_partitions" | sed 's/,$//')] current=[$(tr '\n' ',' <<<"$current_partitions" | sed 's/,$//')]"
}

_cdc_tokens_contain() {
  local text="$1" expected="$2" remaining token
  remaining="${text//$'\t'/$CDC_VERIFIER_HEADER_SEPARATOR}${CDC_VERIFIER_HEADER_SEPARATOR}"
  while [[ "$remaining" == *"$CDC_VERIFIER_HEADER_SEPARATOR"* ]]; do
    token="${remaining%%"$CDC_VERIFIER_HEADER_SEPARATOR"*}"
    remaining="${remaining#*"$CDC_VERIFIER_HEADER_SEPARATOR"}"
    [[ "$token" == "$expected" ]] && return 0
  done
  return 1
}

_cdc_extract_labeled_uint() {
  local metadata="$1" label="$2"
  if [[ "$metadata" =~ ${label}:([0-9]+) ]]; then
    printf '%s\n' "${BASH_REMATCH[1]}"
    return 0
  fi
  _cdc_fail "metadata does not contain ${label}:<number>: $metadata"
}

_cdc_scan_partition_window_for_event() {
  local topic="$1" partition="$2" start_offset="$3" end_offset="$4" event_id="$5"
  local count metadata line observed_partition observed_offset stderr_file scan_status detail

  (( end_offset > start_offset )) || return 1
  count=$((end_offset - start_offset))
  stderr_file="$(mktemp)"

  if metadata="$(_cdc_kafka /opt/kafka/bin/kafka-console-consumer.sh \
      --bootstrap-server "$CDC_KAFKA_BOOTSTRAP" \
      --topic "$topic" \
      --partition "$partition" \
      --offset "$start_offset" \
      --max-messages "$count" \
      --timeout-ms "$CDC_VERIFIER_SCAN_TIMEOUT_MS" \
      --formatter-property print.key=false \
      --formatter-property print.partition=true \
      --formatter-property print.offset=true \
      --formatter-property print.timestamp=false \
      --formatter-property print.headers=true \
      --formatter-property print.value=false \
      --formatter-property "headers.separator=$CDC_VERIFIER_HEADER_SEPARATOR" \
      2>"$stderr_file")"; then
    rm -f "$stderr_file"
  else
    scan_status=$?
    detail="$(tr '\n' ' ' <"$stderr_file" | sed 's/[[:space:]]*$//')"
    rm -f "$stderr_file"
    _cdc_fail \
      "failed to scan Kafka window $topic-$partition [$start_offset,$end_offset) for event $event_id (exit $scan_status): ${detail:-no diagnostic output}"
    return 2
  fi

  while IFS= read -r line; do
    _cdc_tokens_contain "$line" "id:${event_id}" || continue
    observed_partition="$(_cdc_extract_labeled_uint "$line" Partition)" || return 2
    observed_offset="$(_cdc_extract_labeled_uint "$line" Offset)" || return 2
    [[ "$observed_partition" == "$partition" ]] \
      || _cdc_fail \
        "event $event_id reported partition $observed_partition while scanning $partition on $topic" \
      || return 2
    (( observed_offset >= start_offset && observed_offset < end_offset )) \
      || _cdc_fail \
        "event $event_id on $topic offset $observed_offset escaped snapshotted window [$start_offset,$end_offset)" \
      || return 2
    printf '%s\t%s\n' "$observed_partition" "$observed_offset"
    return 0
  done <<<"$metadata"
  return 1
}

_cdc_wait_for_event_after_snapshot() {
  local topic="$1" event_id="$2" baseline_snapshot="$3" result_file="$4"
  local timeout="${5:-$CDC_VERIFIER_TIMEOUT_SECONDS}"
  local deadline now current_snapshot partition end_offset start_offset scan_status

  [[ -s "$baseline_snapshot" ]] \
    || _cdc_fail "baseline snapshot is missing or empty for event $event_id on $topic: $baseline_snapshot" \
    || return 1
  _cdc_is_uint "$timeout" \
    || _cdc_fail "timeout must be an integer number of seconds: $timeout" \
    || return 1

  deadline=$(( $(date +%s) + timeout ))
  current_snapshot="$(mktemp)"

  while :; do
    if ! cdc_capture_topic_end_offsets "$topic" "$current_snapshot"; then
      now="$(date +%s)"
      if (( now >= deadline )); then
        rm -f "$current_snapshot"
        _cdc_fail "timed out reading end offsets for event $event_id on $topic"
        return 1
      fi
      sleep "$CDC_VERIFIER_POLL_INTERVAL_SECONDS"
      continue
    fi

    if ! _cdc_assert_same_partition_set "$topic" "$baseline_snapshot" "$current_snapshot" "$event_id"; then
      rm -f "$current_snapshot"
      return 1
    fi

    while IFS=$'\t' read -r partition end_offset; do
      start_offset="$(_cdc_snapshot_offset_for_partition "$baseline_snapshot" "$partition")" \
        || {
          rm -f "$current_snapshot"
          return 1
        }
      if _cdc_scan_partition_window_for_event \
          "$topic" "$partition" "$start_offset" "$end_offset" "$event_id" >"$result_file"; then
        rm -f "$current_snapshot"
        return 0
      else
        scan_status=$?
        if (( scan_status > 1 )); then
          rm -f "$current_snapshot"
          return 1
        fi
      fi
    done <"$current_snapshot"

    now="$(date +%s)"
    if (( now >= deadline )); then
      printf 'CDC verifier: timed out waiting for event %s on %s after snapshot:\n' "$event_id" "$topic" >&2
      sed 's/^/  baseline /' "$baseline_snapshot" >&2
      sed 's/^/  current  /' "$current_snapshot" >&2
      rm -f "$current_snapshot"
      return 1
    fi
    sleep "$CDC_VERIFIER_POLL_INTERVAL_SECONDS"
  done
}

_cdc_read_record_metadata() {
  local topic="$1" partition="$2" offset="$3"
  _cdc_kafka /opt/kafka/bin/kafka-console-consumer.sh \
    --bootstrap-server "$CDC_KAFKA_BOOTSTRAP" \
    --topic "$topic" \
    --partition "$partition" \
    --offset "$offset" \
    --max-messages 1 \
    --timeout-ms "$CDC_VERIFIER_SCAN_TIMEOUT_MS" \
    --formatter-property print.key=false \
    --formatter-property print.partition=true \
    --formatter-property print.offset=true \
    --formatter-property print.timestamp=true \
    --formatter-property print.headers=false \
    --formatter-property print.value=false 2>/dev/null
}

_cdc_read_record_headers() {
  local topic="$1" partition="$2" offset="$3"
  _cdc_kafka /opt/kafka/bin/kafka-console-consumer.sh \
    --bootstrap-server "$CDC_KAFKA_BOOTSTRAP" \
    --topic "$topic" \
    --partition "$partition" \
    --offset "$offset" \
    --max-messages 1 \
    --timeout-ms "$CDC_VERIFIER_SCAN_TIMEOUT_MS" \
    --formatter-property print.key=false \
    --formatter-property print.partition=false \
    --formatter-property print.offset=false \
    --formatter-property print.timestamp=false \
    --formatter-property print.headers=true \
    --formatter-property print.value=false \
    --formatter-property "headers.separator=$CDC_VERIFIER_HEADER_SEPARATOR" 2>/dev/null
}

_cdc_assert_exact_record_headers() {
  local topic="$1" partition="$2" offset="$3" event_id="$4" expected_header="$5"
  local headers remaining token logical_name run_id
  local event_id_count=0 expected_count=0 logical_name_count=0 task_id_count=0 connector_name_count=0
  local run_id_count=0 header_count=0

  headers="$(_cdc_read_record_headers "$topic" "$partition" "$offset")" || return 1
  remaining="${headers//$'\t'/$CDC_VERIFIER_HEADER_SEPARATOR}${CDC_VERIFIER_HEADER_SEPARATOR}"
  while [[ "$remaining" == *"$CDC_VERIFIER_HEADER_SEPARATOR"* ]]; do
    token="${remaining%%"$CDC_VERIFIER_HEADER_SEPARATOR"*}"
    remaining="${remaining#*"$CDC_VERIFIER_HEADER_SEPARATOR"}"
    [[ -n "$token" ]] || continue
    header_count=$((header_count + 1))
    case "$token" in
      "id:${event_id}") event_id_count=$((event_id_count + 1)) ;;
      "$expected_header") expected_count=$((expected_count + 1)) ;;
      __debezium.context.connectorLogicalName:*)
        logical_name="${token#__debezium.context.connectorLogicalName:}"
        [[ -n "$logical_name" ]] \
          || _cdc_fail "$topic event $event_id has an empty Debezium connector logical-name header at $partition:$offset" \
          || return 1
        logical_name_count=$((logical_name_count + 1))
        ;;
      __debezium.context.taskId:0) task_id_count=$((task_id_count + 1)) ;;
      __debezium.context.connectorName:postgresql) connector_name_count=$((connector_name_count + 1)) ;;
      __debezium.context.runId:*)
        run_id="${token#__debezium.context.runId:}"
        _cdc_is_uuid "$run_id" \
          || _cdc_fail "$topic event $event_id has a non-UUID Debezium runId at $partition:$offset" \
          || return 1
        run_id_count=$((run_id_count + 1))
        ;;
      *)
        _cdc_fail "$topic event $event_id headers at $partition:$offset contain unexpected header '$token'"
        return 1
        ;;
    esac
  done

  if (( header_count != 6
      || event_id_count != 1
      || expected_count != 1
      || logical_name_count != 1
      || task_id_count != 1
      || connector_name_count != 1
      || run_id_count != 1 )); then
    _cdc_fail \
      "$topic event $event_id headers at $partition:$offset do not match the complete known Debezium 3.6 shape"
    return 1
  fi
}

_cdc_record_key_hex() {
  local topic="$1" partition="$2" offset="$3"
  _cdc_kafka /opt/kafka/bin/kafka-console-consumer.sh \
    --bootstrap-server "$CDC_KAFKA_BOOTSTRAP" \
    --topic "$topic" \
    --partition "$partition" \
    --offset "$offset" \
    --max-messages 1 \
    --timeout-ms "$CDC_VERIFIER_SCAN_TIMEOUT_MS" \
    --formatter-property print.key=true \
    --formatter-property print.partition=false \
    --formatter-property print.offset=false \
    --formatter-property print.timestamp=false \
    --formatter-property print.headers=false \
    --formatter-property print.value=false 2>/dev/null \
    | od -An -tx1 \
    | tr -d ' \n'
}

_cdc_assert_record_key() {
  local topic="$1" partition="$2" offset="$3" event_id="$4" expected_key="$5"
  local actual_hex expected_hex
  actual_hex="$(_cdc_record_key_hex "$topic" "$partition" "$offset")" || return 1
  expected_hex="$(printf '%s\n' "$expected_key" | od -An -tx1 | tr -d ' \n')"
  [[ "${actual_hex,,}" == "${expected_hex,,}" ]] \
    || _cdc_fail \
      "$topic event $event_id key bytes mismatch at $partition:$offset: expected '$expected_key'"
}

_cdc_assert_record_metadata() {
  local topic="$1" partition="$2" offset="$3" event_id="$4" expected_key="$5"
  local expected_timestamp="$6" expected_header="$7" expected_partition="${8:-}"
  local metadata observed_partition observed_offset

  metadata="$(_cdc_read_record_metadata "$topic" "$partition" "$offset")" || return 1
  _cdc_assert_exact_record_headers \
    "$topic" "$partition" "$offset" "$event_id" "$expected_header" || return 1
  _cdc_assert_record_key "$topic" "$partition" "$offset" "$event_id" "$expected_key" || return 1
  _cdc_tokens_contain "$metadata" "CreateTime:${expected_timestamp}" \
    || _cdc_fail \
      "$topic event $event_id timestamp mismatch at $partition:$offset: expected CreateTime:${expected_timestamp}" \
    || return 1

  observed_partition="$(_cdc_extract_labeled_uint "$metadata" Partition)" || return 1
  observed_offset="$(_cdc_extract_labeled_uint "$metadata" Offset)" || return 1
  [[ "$observed_partition" == "$partition" ]] \
    || _cdc_fail \
      "$topic event $event_id partition changed between locate and verify: located=$partition observed=$observed_partition" \
    || return 1
  [[ "$observed_offset" == "$offset" ]] \
    || _cdc_fail \
      "$topic event $event_id offset changed between locate and verify: located=$offset observed=$observed_offset" \
    || return 1

  if [[ -n "$expected_partition" && "$expected_partition" != NULL ]]; then
    [[ "$observed_partition" == "$expected_partition" ]] \
      || _cdc_fail \
        "$topic event $event_id explicit partition mismatch: expected $expected_partition observed $observed_partition" \
      || return 1
  fi
}

_cdc_record_value_hex() {
  local topic="$1" partition="$2" offset="$3"
  _cdc_kafka /opt/kafka/bin/kafka-console-consumer.sh \
    --bootstrap-server "$CDC_KAFKA_BOOTSTRAP" \
    --topic "$topic" \
    --partition "$partition" \
    --offset "$offset" \
    --max-messages 1 \
    --timeout-ms "$CDC_VERIFIER_SCAN_TIMEOUT_MS" \
    --formatter-property print.key=false \
    --formatter-property print.partition=false \
    --formatter-property print.offset=false \
    --formatter-property print.timestamp=false \
    --formatter-property print.headers=false \
    --formatter-property print.value=true 2>/dev/null \
    | od -An -tx1 \
    | tr -d ' \n'
}

_cdc_assert_record_value_hex() {
  local topic="$1" partition="$2" offset="$3" event_id="$4" expected_payload_hex="$5"
  local expected_payload_sha="$6" actual_console_hex actual_payload_hex actual_sha
  local expected_bytes observed_bytes
  [[ "$expected_payload_hex" =~ ^([0-9a-fA-F]{2})+$ ]] \
    || _cdc_fail "$topic event $event_id expected payload is not hexadecimal" \
    || return 1

  actual_console_hex="$(_cdc_record_value_hex "$topic" "$partition" "$offset")" || return 1
  [[ "$actual_console_hex" == *0a ]] \
    || _cdc_fail "$topic event $event_id Kafka console output omitted its record separator at $partition:$offset" \
    || return 1
  actual_payload_hex="${actual_console_hex%0a}"
  if [[ "${actual_payload_hex,,}" != "${expected_payload_hex,,}" ]]; then
    actual_sha="$(_cdc_payload_sha256 "$actual_payload_hex")" || return 1
    expected_bytes=$(( ${#expected_payload_hex} / 2 ))
    observed_bytes=$(( ${#actual_payload_hex} / 2 ))
    _cdc_fail \
      "$topic event $event_id payload mismatch at $partition:$offset: expected_sha256=$expected_payload_sha observed_sha256=$actual_sha expected_bytes=$expected_bytes observed_bytes=$observed_bytes"
    return 1
  fi
}

_cdc_assert_record_contract() {
  local topic="$1" event_id="$2" baseline_snapshot="$3" expected_key="$4"
  local expected_timestamp="$5" expected_header="$6" expected_payload_hex="$7"
  local expected_payload_sha="$8" expected_partition="${9:-}" result_file partition offset

  result_file="$(mktemp)"
  if ! _cdc_wait_for_event_after_snapshot \
      "$topic" "$event_id" "$baseline_snapshot" "$result_file"; then
    rm -f "$result_file"
    return 1
  fi
  IFS=$'\t' read -r partition offset <"$result_file"
  if ! _cdc_assert_record_metadata \
      "$topic" "$partition" "$offset" "$event_id" "$expected_key" \
      "$expected_timestamp" "$expected_header" "$expected_partition"; then
    rm -f "$result_file"
    return 1
  fi
  if ! _cdc_assert_record_value_hex \
      "$topic" "$partition" "$offset" "$event_id" "$expected_payload_hex" "$expected_payload_sha"; then
    rm -f "$result_file"
    return 1
  fi
  printf '%s\t%s\n' "$partition" "$offset"
  rm -f "$result_file"
}

cdc_assert_probe_publication() {
  local probe="$1" baseline_snapshot="$2"
  local event_id topic message_key timestamp_ms headers_json payload_hex payload_sha partition
  _cdc_validate_probe "$probe" || return 1
  event_id="$(_cdc_probe_field "$probe" event_id)"
  topic="$(_cdc_probe_field "$probe" topic)"
  message_key="$(_cdc_probe_field "$probe" message_key)"
  timestamp_ms="$(_cdc_probe_field "$probe" created_at_unix_ms)"
  headers_json="$(_cdc_probe_field "$probe" headers_json)"
  payload_hex="$(_cdc_probe_field "$probe" payload_hex)"
  payload_sha="$(_cdc_probe_field "$probe" payload_sha256)"
  partition="$(_cdc_probe_field "$probe" explicit_partition)"
  [[ "$partition" == null ]] && partition=''

  _cdc_assert_record_contract \
    "$topic" "$event_id" "$baseline_snapshot" "$message_key" "$timestamp_ms" \
    "headers_json:${headers_json}" "$payload_hex" "$payload_sha" "$partition"
}
