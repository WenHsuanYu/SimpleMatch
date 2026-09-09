#!/usr/bin/env bash

# Deep CDC verification Module.
#
# Interface:
#   1. cdc_capture_topic_end_offsets <topic> <snapshot-file>
#   2. cdc_wait_for_connector_state <connector-name> <expected-state> [timeout-seconds]
#   3. cdc_capture_outbox_baseline <schema> <aggregate-type> <aggregate-id> <baseline-file>
#   4. cdc_read_outbox_probe <schema> <aggregate-type> <aggregate-id> <probe-file> [baseline-file]
#   5. cdc_assert_same_probe <expected-probe> <observed-probe>
#   6. cdc_assert_probe_publication <probe-file> <baseline-snapshot> [publication-evidence-file]
#   7. cdc_validate_probe <probe-file>
#   8. cdc_validate_publication_evidence <publication-evidence-file>
#
# The CdcProbeIdentity is a test-side JSON document produced by cdc_read_outbox_probe. It carries
# the durable outbox identity and immutable publication contract so scenario callers do not rebuild
# Kafka assertions field-by-field. Its payload_hex field is sensitive implementation state used only
# for byte-exact comparison and must never be printed in diagnostics.
#
# Ordering and invariants:
#   - Capture the Kafka baseline before committing or publishing the event under test.
#   - Capture the existing outbox event identities before a transition when an aggregate can have
#     lifecycle history, then read exactly one row that is absent from that baseline.
#   - Read the committed outbox row by stable business/aggregate identity through CDC_OUTBOX_EXEC.
#   - The topic partition set must remain unchanged between baseline and verification.
#   - Event identity is the exact Debezium EventRouter `id` header; Kafka key text never locates it.
#   - A supplied outbox partition is authoritative; a NULL partition is discovered, never guessed.
#   - RUNNING connector state is prerequisite evidence only; publication success is separate.
#
# Adapters at the external seams:
#   - CDC_KAFKA_EXEC executes Kafka CLI commands.
#   - CDC_OUTBOX_EXEC receives one Module-owned SQL string and returns tab-separated rows. Baseline
#     capture returns one event_id per row; probe lookup returns the ten-field publication row.
#   - CDC_CONNECT_STATUS_EXEC receives one connector name and returns its Connect status JSON.
# The Compose harness, this Module's fakes, and the #156 focused Kubernetes runner are concrete
# Adapters without changing this Interface or copying its observation logic.
#
# Error modes and performance:
#   - CDC_VERIFIER_TIMEOUT_SECONDS bounds polling and CDC_VERIFIER_SCAN_TIMEOUT_MS bounds each scan.
#   - Kafka/Connect/outbox failures, topology drift, missing rows, and exact-record mismatches fail
#     closed. Diagnostics identify event/topic/location where known, but never print raw payloads.

cdc_configure_adapters() {
  local kafka_adapter="$1" outbox_adapter="$2" connect_status_adapter="$3"

  [[ "$kafka_adapter" =~ ^[A-Za-z_][A-Za-z0-9_]*$ &&
    "$outbox_adapter" =~ ^[A-Za-z_][A-Za-z0-9_]*$ &&
    "$connect_status_adapter" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]] || return 2
  CDC_KAFKA_EXEC=("$kafka_adapter")
  CDC_OUTBOX_EXEC=("$outbox_adapter")
  CDC_CONNECT_STATUS_EXEC=("$connect_status_adapter")
}

CDC_KAFKA_BOOTSTRAP="${CDC_KAFKA_BOOTSTRAP:-kafka:29092}"
CDC_VERIFIER_TIMEOUT_SECONDS="${CDC_VERIFIER_TIMEOUT_SECONDS:-30}"
CDC_VERIFIER_POLL_INTERVAL_SECONDS="${CDC_VERIFIER_POLL_INTERVAL_SECONDS:-1}"
CDC_VERIFIER_SCAN_TIMEOUT_MS="${CDC_VERIFIER_SCAN_TIMEOUT_MS:-2000}"
CDC_VERIFIER_HEADER_SEPARATOR="${CDC_VERIFIER_HEADER_SEPARATOR:-__SIMPLEMATCH_CDC_HEADER__}"
CDC_PUBLICATION_EVIDENCE_SCHEMA_VERSION=2

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

_cdc_hex_sha256() {
  local value_hex="$1"
  [[ -n "$value_hex" && "$value_hex" =~ ^([0-9a-fA-F]{2})+$ ]] ||
    _cdc_fail 'hex value is missing or is not hexadecimal' || return 1
  command -v xxd >/dev/null 2>&1 || _cdc_fail 'xxd is required for hex hashing' || return 1
  command -v sha256sum >/dev/null 2>&1 ||
    _cdc_fail 'sha256sum is required for hex hashing' || return 1
  printf '%s' "$value_hex" | xxd -r -p | sha256sum | awk '{print $1}'
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
      (.payload_type | type == "string" and length > 0) and
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

cdc_validate_probe() {
  _cdc_validate_probe "$1"
}

cdc_validate_publication_evidence() {
  local evidence_file="$1" expected_key expected_key_hex expected_key_sha

  [[ -s "$evidence_file" ]] ||
    _cdc_fail "publication evidence is missing or empty: $evidence_file" || return 1
  jq -e 'type == "object"' "$evidence_file" >/dev/null 2>&1 ||
    _cdc_fail "publication evidence must be one JSON object: $evidence_file" || return 1
  expected_key="$(jq -er '.expected_message_key |
    select(type == "string" and length > 0)' "$evidence_file")" ||
    _cdc_fail "publication evidence has no expected message key: $evidence_file" || return 1
  expected_key_hex="$(printf '%s\n' "$expected_key" | od -An -tx1 | tr -d ' \n')" ||
    _cdc_fail "could not encode the expected message key: $evidence_file" || return 1
  expected_key_sha="$(_cdc_hex_sha256 "$expected_key_hex")" || return 1
  jq -e \
      --argjson schema_version "$CDC_PUBLICATION_EVIDENCE_SCHEMA_VERSION" \
      --arg expected_key_sha "$expected_key_sha" '
      .schema_version == $schema_version and
      .status == "PASS" and
      (.topic | type == "string" and length > 0) and
      (.event_id | type == "string" and test("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")) and
      (.partition | type == "number" and floor == . and . >= 0) and
      (.offset | type == "number" and floor == . and . >= 0) and
      (.expected_message_key | type == "string" and length > 0) and
      (.expected_timestamp_unix_ms | type == "number" and floor == . and . >= 0) and
      (.expected_headers_json_sha256 | type == "string" and test("^[0-9a-f]{64}$")) and
      (.expected_event_type | type == "string" and length > 0) and
      (.expected_payload_sha256 | type == "string" and test("^[0-9a-f]{64}$")) and
      (.observed | type == "object") and
      (.observed.partition == .partition) and
      (.observed.offset == .offset) and
      (.observed.timestamp_unix_ms == .expected_timestamp_unix_ms) and
      (.observed.key_sha256 == $expected_key_sha) and
      (.observed.payload_sha256 == .expected_payload_sha256) and
      (.observed.headers_json_sha256 == .expected_headers_json_sha256) and
      (.observed.event_id == .event_id) and
      (.observed.event_type == .expected_event_type) and
      (.observed.header_count == 7) and
      (.observed.headers_sha256 | type == "string" and test("^[0-9a-f]{64}$")) and
      (.observed.key_sha256 | type == "string" and test("^[0-9a-f]{64}$")) and
      (.observed.payload_sha256 | type == "string" and test("^[0-9a-f]{64}$")) and
      (.observed.headers_json_sha256 | type == "string" and test("^[0-9a-f]{64}$")) and
      (.observed.timestamp_unix_ms | type == "number" and floor == . and . >= 0) and
      (.verification | type == "object") and
      (.verification.headers_exact == true) and
      (.verification.key_exact == true) and
      (.verification.timestamp_exact == true) and
      (.verification.payload_exact == true)
    ' "$evidence_file" >/dev/null 2>&1 ||
    _cdc_fail "publication evidence contract is invalid: $evidence_file" || return 1
}

_cdc_validate_outbox_locator() {
  local schema="$1" aggregate_type="$2" aggregate_id="$3"

  [[ "$schema" =~ ^[a-z][a-z0-9_]*$ ]] \
    || _cdc_fail "invalid outbox schema name: $schema" \
    || return 1
  [[ "$aggregate_type" =~ ^[A-Za-z][A-Za-z0-9_.-]*$ ]] \
    || _cdc_fail "invalid outbox aggregate type: $aggregate_type" \
    || return 1
  [[ "$aggregate_id" =~ ^[A-Za-z0-9][A-Za-z0-9_.:-]*$ ]] \
    || _cdc_fail "invalid outbox business identity for $aggregate_type" \
    || return 1
}

_cdc_validate_outbox_baseline() {
  local baseline="$1" schema="$2" aggregate_type="$3" aggregate_id="$4"

  [[ -s "$baseline" ]] \
    || _cdc_fail "outbox baseline is missing or empty: $baseline" \
    || return 1
  jq -e '
      (.schema_version == 1)
      and (.event_ids | type == "array")
      and (.event_ids | all(.[];
        type == "string" and
        test("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")))
      and ((.event_ids | unique | length) == (.event_ids | length))
    ' "$baseline" >/dev/null \
    || _cdc_fail "outbox baseline contract is invalid: $baseline" \
    || return 1
  jq -e \
    --arg schema "$schema" \
    --arg aggregate_type "$aggregate_type" \
    --arg aggregate_id "$aggregate_id" '
      .schema == $schema
      and .aggregate_type == $aggregate_type
      and .aggregate_id == $aggregate_id
    ' "$baseline" >/dev/null \
    || _cdc_fail \
      "outbox baseline aggregate identity mismatch: expected ${schema}/${aggregate_type}/${aggregate_id}" \
    || return 1
}

_cdc_baseline_event_ids_sql() {
  local baseline="$1" event_id event_ids_sql=''

  while IFS= read -r event_id; do
    [[ -n "$event_id" ]] || continue
    if [[ -n "$event_ids_sql" ]]; then
      event_ids_sql+=', '
    fi
    # _cdc_validate_outbox_baseline has already restricted every value to a UUID.
    event_ids_sql+="'$event_id'"
  done < <(jq -r '.event_ids[]' "$baseline")
  printf '%s' "$event_ids_sql"
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

cdc_capture_outbox_baseline() {
  local schema="$1" aggregate_type="$2" aggregate_id="$3" output="$4"
  local sql raw line event_ids_json
  local -a event_ids=()

  _cdc_validate_outbox_locator "$schema" "$aggregate_type" "$aggregate_id" || return 1
  [[ -n "$output" ]] || _cdc_fail 'outbox baseline output path is required' || return 1

  sql="SELECT event_id::text FROM ${schema}.outbox WHERE aggregate_type = '${aggregate_type}' AND aggregate_id = '${aggregate_id}' ORDER BY event_id"
  raw="$(_cdc_outbox "$sql")" || {
    _cdc_fail \
      "failed to capture durable ${aggregate_type} outbox baseline for business identity $aggregate_id from ${schema}.outbox"
    return 1
  }
  while IFS= read -r line; do
    [[ -n "$line" ]] || continue
    [[ "$line" != *$'\t'* ]] \
      || _cdc_fail "outbox baseline returned more than one column for $aggregate_type/$aggregate_id" \
      || return 1
    _cdc_is_uuid "$line" \
      || _cdc_fail "outbox baseline returned an invalid event identity for $aggregate_type/$aggregate_id" \
      || return 1
    event_ids+=("$line")
  done <<<"$raw"

  if ((${#event_ids[@]} == 0)); then
    event_ids_json='[]'
  else
    event_ids_json="$(printf '%s\n' "${event_ids[@]}" | jq -Rsc 'split("\n") | map(select(length > 0))')" || return 1
  fi
  jq -n \
    --argjson schema_version 1 \
    --arg schema "$schema" \
    --arg aggregate_type "$aggregate_type" \
    --arg aggregate_id "$aggregate_id" \
    --argjson event_ids "$event_ids_json" \
    '{schema_version:$schema_version,schema:$schema,aggregate_type:$aggregate_type,
      aggregate_id:$aggregate_id,event_ids:$event_ids}' >"$output" || return 1
  _cdc_validate_outbox_baseline "$output" "$schema" "$aggregate_type" "$aggregate_id"
}

cdc_read_outbox_probe() {
  local schema="$1" aggregate_type="$2" aggregate_id="$3" output="$4"
  local baseline_file="${5:-}" sql raw row_count baseline_event_ids_sql exclusion_sql=''
  local event_id topic message_key partition payload_hex payload_type timestamp_ms headers_json
  local observed_aggregate_type observed_aggregate_id payload_sha reservation_id='' account_id=''
  local partition_json='null'

  _cdc_validate_outbox_locator "$schema" "$aggregate_type" "$aggregate_id" || return 1
  [[ -n "$output" ]] || _cdc_fail 'probe output path is required' || return 1

  if [[ -n "$baseline_file" ]]; then
    _cdc_validate_outbox_baseline \
      "$baseline_file" "$schema" "$aggregate_type" "$aggregate_id" || return 1
    baseline_event_ids_sql="$(_cdc_baseline_event_ids_sql "$baseline_file")"
    if [[ -n "$baseline_event_ids_sql" ]]; then
      exclusion_sql=" AND event_id::text NOT IN (${baseline_event_ids_sql})"
    fi
  fi
  sql="SELECT event_id::text, topic, message_key, COALESCE(kafka_partition_id::text, 'NULL'), encode(payload, 'hex'), payload_type, round(extract(epoch from created_at) * 1000)::bigint, headers_json, aggregate_type, aggregate_id FROM ${schema}.outbox WHERE aggregate_type = '${aggregate_type}' AND aggregate_id = '${aggregate_id}'${exclusion_sql} ORDER BY created_at, event_id"
  raw="$(_cdc_outbox "$sql")" || {
    _cdc_fail \
      "failed to read durable ${aggregate_type} outbox event for business identity $aggregate_id from ${schema}.outbox"
    return 1
  }
  row_count="$(printf '%s\n' "$raw" | awk 'NF { count++ } END { print count + 0 }')"
  if [[ -n "$baseline_file" ]]; then
    [[ "$row_count" == 1 ]] \
      || _cdc_fail \
        "durable ${aggregate_type} outbox transition for business identity $aggregate_id in ${schema}.outbox: expected exactly one post-baseline row, observed $row_count" \
      || return 1
  else
    [[ "$row_count" == 1 ]] \
      || _cdc_fail \
        "durable ${aggregate_type} outbox event for business identity $aggregate_id in ${schema}.outbox: expected exactly one row, observed $row_count" \
      || return 1
  fi

  IFS=$'\t' read -r event_id topic message_key partition payload_hex payload_type timestamp_ms headers_json \
    observed_aggregate_type observed_aggregate_id <<<"$raw"
  _cdc_is_uuid "$event_id" \
    || _cdc_fail \
      "durable ${aggregate_type} outbox event for business identity $aggregate_id has invalid event identity" \
    || return 1
  [[ "$observed_aggregate_type" == "$aggregate_type" && "$observed_aggregate_id" == "$aggregate_id" ]] \
    || _cdc_fail \
      "outbox business identity mismatch for event $event_id: expected ${aggregate_type}/$aggregate_id observed ${observed_aggregate_type}/${observed_aggregate_id}" \
    || return 1
  [[ -n "$topic" && -n "$message_key" && -n "$payload_type" && -n "$headers_json" ]] \
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
    --arg payload_type "$payload_type" \
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
      payload_type: $payload_type,
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
      payload_type created_at_unix_ms headers_json aggregate_type explicit_partition; do
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
  local found=false fatal_scan=false

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
          fatal_scan=true
          break
        }
      if _cdc_scan_partition_window_for_event \
          "$topic" "$partition" "$start_offset" "$end_offset" "$event_id" >"$result_file"; then
        found=true
        break
      else
        scan_status=$?
        if (( scan_status > 1 )); then
          fatal_scan=true
          break
        fi
      fi
    done <"$current_snapshot"

    if [[ "$found" == true ]]; then
      rm -f "$current_snapshot"
      return 0
    fi
    if [[ "$fatal_scan" == true ]]; then
      rm -f "$current_snapshot"
      return 1
    fi

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

_cdc_validate_exact_record_headers() {
  local topic="$1" partition="$2" offset="$3" event_id="$4" expected_header="$5"
  local expected_event_type="$6" headers="$7"
  local remaining token logical_name run_id headers_sha headers_json_sha
  local observed_event_id='' observed_event_type='' observed_headers_json=''
  local observed_logical_name='' observed_task_id='' observed_connector_name='' observed_run_id=''
  local event_id_count=0 expected_count=0 event_type_count=0 logical_name_count=0 task_id_count=0 connector_name_count=0
  local run_id_count=0 header_count=0

  remaining="${headers//$'\t'/$CDC_VERIFIER_HEADER_SEPARATOR}${CDC_VERIFIER_HEADER_SEPARATOR}"
  while [[ "$remaining" == *"$CDC_VERIFIER_HEADER_SEPARATOR"* ]]; do
    token="${remaining%%"$CDC_VERIFIER_HEADER_SEPARATOR"*}"
    remaining="${remaining#*"$CDC_VERIFIER_HEADER_SEPARATOR"}"
    [[ -n "$token" ]] || continue
    header_count=$((header_count + 1))
    case "$token" in
      "id:${event_id}")
        event_id_count=$((event_id_count + 1))
        observed_event_id="$event_id"
        ;;
      "$expected_header")
        expected_count=$((expected_count + 1))
        observed_headers_json="${token#headers_json:}"
        ;;
      "$expected_event_type")
        event_type_count=$((event_type_count + 1))
        observed_event_type="${token#eventType:}"
        ;;
      __debezium.context.connectorLogicalName:*)
        logical_name="${token#__debezium.context.connectorLogicalName:}"
        [[ -n "$logical_name" ]] \
          || _cdc_fail "$topic event $event_id has an empty Debezium connector logical-name header at $partition:$offset" \
          || return 1
        logical_name_count=$((logical_name_count + 1))
        observed_logical_name="$logical_name"
        ;;
      __debezium.context.taskId:0)
        task_id_count=$((task_id_count + 1))
        observed_task_id=0
        ;;
      __debezium.context.connectorName:postgresql)
        connector_name_count=$((connector_name_count + 1))
        observed_connector_name=postgresql
        ;;
      __debezium.context.runId:*)
        run_id="${token#__debezium.context.runId:}"
        _cdc_is_uuid "$run_id" \
          || _cdc_fail "$topic event $event_id has a non-UUID Debezium runId at $partition:$offset" \
          || return 1
        run_id_count=$((run_id_count + 1))
        observed_run_id="$run_id"
        ;;
      *)
        _cdc_fail "$topic event $event_id headers at $partition:$offset contain unexpected header '$token'"
        return 1
        ;;
    esac
  done

  if (( header_count != 7
      || event_id_count != 1
      || expected_count != 1
      || event_type_count != 1
      || logical_name_count != 1
      || task_id_count != 1
      || connector_name_count != 1
      || run_id_count != 1 )); then
    _cdc_fail \
      "$topic event $event_id headers at $partition:$offset do not match the complete known Debezium 3.6 shape"
    return 1
  fi

  headers_sha="$(printf '%s' "$headers" | sha256sum | awk '{print $1}')" || return 1
  headers_json_sha="$(printf '%s' "$observed_headers_json" |
    sha256sum | awk '{print $1}')" || return 1
  jq -n \
    --arg event_id "$observed_event_id" \
    --arg event_type "$observed_event_type" \
    --arg headers_sha "$headers_sha" \
    --arg headers_json_sha "$headers_json_sha" \
    --arg logical_name "$observed_logical_name" \
    --arg connector_name "$observed_connector_name" \
    --arg run_id "$observed_run_id" \
    --argjson header_count "$header_count" \
    --argjson task_id "$observed_task_id" \
    '{event_id:$event_id,event_type:$event_type,headers_sha256:$headers_sha,
      headers_json_sha256:$headers_json_sha,header_count:$header_count,
      connector_logical_name:$logical_name,task_id:$task_id,
      connector_name:$connector_name,run_id:$run_id}'
}

_cdc_assert_exact_record_headers() {
  local topic="$1" partition="$2" offset="$3" event_id="$4" expected_header="$5"
  local expected_event_type="$6" headers

  headers="$(_cdc_read_record_headers "$topic" "$partition" "$offset")" || return 1
  _cdc_validate_exact_record_headers \
    "$topic" "$partition" "$offset" "$event_id" "$expected_header" \
    "$expected_event_type" "$headers" >/dev/null
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

_cdc_validate_record_key_hex() {
  local topic="$1" partition="$2" offset="$3" event_id="$4" expected_key="$5" actual_hex="$6"
  local expected_hex
  expected_hex="$(printf '%s\n' "$expected_key" | od -An -tx1 | tr -d ' \n')"
  [[ "${actual_hex,,}" == "${expected_hex,,}" ]] \
    || _cdc_fail \
      "$topic event $event_id key bytes mismatch at $partition:$offset: expected '$expected_key'"
}

_cdc_assert_record_key() {
  local topic="$1" partition="$2" offset="$3" event_id="$4" expected_key="$5"
  local actual_hex
  actual_hex="$(_cdc_record_key_hex "$topic" "$partition" "$offset")" || return 1
  _cdc_validate_record_key_hex \
    "$topic" "$partition" "$offset" "$event_id" "$expected_key" "$actual_hex"
}

_cdc_validate_record_metadata() {
  local topic="$1" partition="$2" offset="$3" event_id="$4"
  local expected_timestamp="$5" expected_partition="${6:-}"
  local metadata="$7" observed_partition observed_offset

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

_cdc_assert_record_metadata() {
  local topic="$1" partition="$2" offset="$3" event_id="$4" expected_key="$5"
  local expected_timestamp="$6" expected_header="$7" expected_event_type="$8"
  local expected_partition="${9:-}"
  local metadata

  metadata="$(_cdc_read_record_metadata "$topic" "$partition" "$offset")" || return 1
  _cdc_assert_exact_record_headers \
    "$topic" "$partition" "$offset" "$event_id" "$expected_header" \
    "$expected_event_type" || return 1
  _cdc_assert_record_key "$topic" "$partition" "$offset" "$event_id" "$expected_key" || return 1
  _cdc_validate_record_metadata \
    "$topic" "$partition" "$offset" "$event_id" "$expected_timestamp" \
    "$expected_partition" "$metadata"
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

_cdc_validate_record_value_hex() {
  local topic="$1" partition="$2" offset="$3" event_id="$4" expected_payload_hex="$5"
  local expected_payload_sha="$6" actual_console_hex="$7" actual_payload_hex actual_sha
  local expected_bytes observed_bytes
  [[ "$expected_payload_hex" =~ ^([0-9a-fA-F]{2})+$ ]] \
    || _cdc_fail "$topic event $event_id expected payload is not hexadecimal" \
    || return 1

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

_cdc_assert_record_value_hex() {
  local topic="$1" partition="$2" offset="$3" event_id="$4" expected_payload_hex="$5"
  local expected_payload_sha="$6" actual_console_hex
  actual_console_hex="$(_cdc_record_value_hex "$topic" "$partition" "$offset")" || return 1
  _cdc_validate_record_value_hex \
    "$topic" "$partition" "$offset" "$event_id" "$expected_payload_hex" \
    "$expected_payload_sha" "$actual_console_hex"
}

_cdc_assert_record_contract() {
  local topic="$1" event_id="$2" baseline_snapshot="$3" expected_key="$4"
  local expected_timestamp="$5" expected_header="$6" expected_event_type="$7"
  local expected_payload_hex="$8" expected_payload_sha="$9"
  local expected_partition="${10:-}" publication_evidence="${11:-}"
  local result_file partition offset expected_headers_sha expected_key_hex expected_key_sha
  local metadata headers key_hex value_console_hex actual_payload_hex actual_payload_sha
  local header_observation observed_headers_sha observed_headers_json_sha observed_event_id
  local observed_event_type observed_header_count observed_timestamp observed_key_sha
  local header_observation_file
  local expected_payload_type="${expected_event_type#eventType:}"

  result_file="$(mktemp)"
  if ! _cdc_wait_for_event_after_snapshot \
      "$topic" "$event_id" "$baseline_snapshot" "$result_file"; then
    rm -f "$result_file"
    return 1
  fi
  IFS=$'\t' read -r partition offset <"$result_file"
  header_observation_file="$(mktemp)" || {
    rm -f "$result_file"
    _cdc_fail 'could not allocate Kafka header observation evidence'
    return 1
  }
  metadata="$(_cdc_read_record_metadata "$topic" "$partition" "$offset")" || {
    rm -f "$header_observation_file" "$result_file"
    return 1
  }
  headers="$(_cdc_read_record_headers "$topic" "$partition" "$offset")" || {
    rm -f "$header_observation_file" "$result_file"
    return 1
  }
  key_hex="$(_cdc_record_key_hex "$topic" "$partition" "$offset")" || {
    rm -f "$header_observation_file" "$result_file"
    return 1
  }
  value_console_hex="$(_cdc_record_value_hex "$topic" "$partition" "$offset")" || {
    rm -f "$header_observation_file" "$result_file"
    return 1
  }
  if ! _cdc_validate_record_metadata \
      "$topic" "$partition" "$offset" "$event_id" "$expected_timestamp" \
      "$expected_partition" "$metadata" ||
    ! _cdc_validate_exact_record_headers \
      "$topic" "$partition" "$offset" "$event_id" "$expected_header" \
      "$expected_event_type" "$headers" >"$header_observation_file"; then
    rm -f "$header_observation_file" "$result_file"
    return 1
  fi
  header_observation="$(cat "$header_observation_file")"
  rm -f "$header_observation_file"
  if ! _cdc_validate_record_key_hex \
      "$topic" "$partition" "$offset" "$event_id" "$expected_key" "$key_hex" ||
    ! _cdc_validate_record_value_hex \
      "$topic" "$partition" "$offset" "$event_id" "$expected_payload_hex" \
      "$expected_payload_sha" "$value_console_hex"; then
    rm -f "$result_file"
    return 1
  fi
  [[ "$value_console_hex" == *0a ]] || {
    rm -f "$header_observation_file" "$result_file"
    return 1
  }
  actual_payload_hex="${value_console_hex%0a}"
  actual_payload_sha="$(_cdc_payload_sha256 "$actual_payload_hex")" || {
    rm -f "$header_observation_file" "$result_file"
    return 1
  }
  if [[ -n "$publication_evidence" ]]; then
    expected_headers_sha="$(printf '%s' "${expected_header#headers_json:}" |
      sha256sum | awk '{print $1}')" || {
      rm -f "$result_file"
      _cdc_fail 'could not hash the verified Kafka headers'
      return 1
    }
    expected_key_hex="$(printf '%s\n' "$expected_key" | od -An -tx1 | tr -d ' \n')" || {
      rm -f "$result_file"
      _cdc_fail 'could not encode the verified Kafka key'
      return 1
    }
    expected_key_sha="$(_cdc_hex_sha256 "$expected_key_hex")" || {
      rm -f "$result_file"
      return 1
    }
    observed_headers_sha="$(jq -er '.headers_sha256' <<<"$header_observation")" || {
      rm -f "$result_file"
      return 1
    }
    observed_headers_json_sha="$(jq -er '.headers_json_sha256' <<<"$header_observation")" || {
      rm -f "$result_file"
      return 1
    }
    observed_event_id="$(jq -er '.event_id' <<<"$header_observation")" || {
      rm -f "$result_file"
      return 1
    }
    observed_event_type="$(jq -er '.event_type' <<<"$header_observation")" || {
      rm -f "$result_file"
      return 1
    }
    observed_header_count="$(jq -er '.header_count' <<<"$header_observation")" || {
      rm -f "$result_file"
      return 1
    }
    observed_timestamp="$(_cdc_extract_labeled_uint "$metadata" CreateTime)" || {
      rm -f "$result_file"
      return 1
    }
    observed_key_sha="$(_cdc_hex_sha256 "$key_hex")" || {
      rm -f "$result_file"
      return 1
    }
    jq -n \
      --argjson schema_version "$CDC_PUBLICATION_EVIDENCE_SCHEMA_VERSION" \
      --arg topic "$topic" --arg event_id "$event_id" \
      --argjson partition "$partition" --argjson offset "$offset" \
      --arg message_key "$expected_key" \
      --argjson timestamp "$expected_timestamp" \
      --arg headers_sha "$expected_headers_sha" \
      --arg event_type "$expected_payload_type" \
      --arg payload_sha "$expected_payload_sha" \
      --arg observed_headers_sha "$observed_headers_sha" \
      --arg observed_headers_json_sha "$observed_headers_json_sha" \
      --arg observed_event_id "$observed_event_id" \
      --arg observed_event_type "$observed_event_type" \
      --arg observed_key_sha "$observed_key_sha" \
      --arg observed_payload_sha "$actual_payload_sha" \
      --argjson observed_timestamp "$observed_timestamp" \
      --argjson observed_header_count "$observed_header_count" \
      '{schema_version:$schema_version,status:"PASS",topic:$topic,event_id:$event_id,
        partition:$partition,offset:$offset,expected_message_key:$message_key,
        expected_timestamp_unix_ms:$timestamp,expected_headers_json_sha256:$headers_sha,
        expected_event_type:$event_type,expected_payload_sha256:$payload_sha,
        observed:{partition:$partition,offset:$offset,timestamp_unix_ms:$observed_timestamp,
          key_sha256:$observed_key_sha,payload_sha256:$observed_payload_sha,
          headers_sha256:$observed_headers_sha,headers_json_sha256:$observed_headers_json_sha,
          event_id:$observed_event_id,event_type:$observed_event_type,
          header_count:$observed_header_count},
        verification:{headers_exact:true,key_exact:true,timestamp_exact:true,payload_exact:true}}' \
      >"$publication_evidence" || {
        rm -f "$result_file"
        _cdc_fail "could not write publication evidence: $publication_evidence"
        return 1
      }
    cdc_validate_publication_evidence "$publication_evidence" || {
      rm -f "$result_file"
      return 1
    }
  fi
  printf '%s\t%s\n' "$partition" "$offset"
  rm -f "$result_file"
}

cdc_assert_probe_publication() {
  local probe="$1" baseline_snapshot="$2"
  local publication_evidence="${3:-}"
  local event_id topic message_key timestamp_ms headers_json payload_type payload_hex payload_sha partition
  _cdc_validate_probe "$probe" || return 1
  event_id="$(_cdc_probe_field "$probe" event_id)"
  topic="$(_cdc_probe_field "$probe" topic)"
  message_key="$(_cdc_probe_field "$probe" message_key)"
  timestamp_ms="$(_cdc_probe_field "$probe" created_at_unix_ms)"
  headers_json="$(_cdc_probe_field "$probe" headers_json)"
  payload_type="$(_cdc_probe_field "$probe" payload_type)"
  payload_hex="$(_cdc_probe_field "$probe" payload_hex)"
  payload_sha="$(_cdc_probe_field "$probe" payload_sha256)"
  partition="$(_cdc_probe_field "$probe" explicit_partition)"
  [[ "$partition" == null ]] && partition=''

  _cdc_assert_record_contract \
    "$topic" "$event_id" "$baseline_snapshot" "$message_key" "$timestamp_ms" \
    "headers_json:${headers_json}" "eventType:${payload_type}" \
    "$payload_hex" "$payload_sha" "$partition" "$publication_evidence"
}
