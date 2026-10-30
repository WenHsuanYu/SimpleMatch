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
  _cdc_prepare_java
}

CDC_KAFKA_BOOTSTRAP="${CDC_KAFKA_BOOTSTRAP:-kafka:29092}"
CDC_VERIFIER_TIMEOUT_SECONDS="${CDC_VERIFIER_TIMEOUT_SECONDS:-30}"
CDC_VERIFIER_POLL_INTERVAL_SECONDS="${CDC_VERIFIER_POLL_INTERVAL_SECONDS:-1}"
CDC_VERIFIER_SCAN_TIMEOUT_MS="${CDC_VERIFIER_SCAN_TIMEOUT_MS:-2000}"
CDC_VERIFIER_HEADER_SEPARATOR="${CDC_VERIFIER_HEADER_SEPARATOR:-__SIMPLEMATCH_CDC_HEADER__}"
CDC_PUBLICATION_EVIDENCE_SCHEMA_VERSION=2
_CDC_JAVA_READY=0
_CDC_JAVA_REPO_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"

_cdc_prepare_java() {
  if [[ "$_CDC_JAVA_READY" != 1 ]]; then
    "$_CDC_JAVA_REPO_ROOT/gradlew" -p "$_CDC_JAVA_REPO_ROOT" -q \
      :tools:recovery-evidence-verifier:installDist >&2 || return 1
    _CDC_JAVA_READY=1
  fi
}

_cdc_java() {
  _cdc_prepare_java || return 1
  "$_CDC_JAVA_REPO_ROOT/tools/recovery-evidence-verifier/build/install/recovery-evidence-verifier/bin/recovery-evidence-verifier" cdc "$@"
}

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

_cdc_probe_field() {
  local probe="$1" field="$2"
  jq -r --arg field "$field" '.[$field] // "null"' "$probe"
}

_cdc_validate_probe() {
  _cdc_java probe "$1" >/dev/null
}

cdc_validate_probe() {
  _cdc_validate_probe "$1"
}

cdc_validate_publication_evidence() {
  _cdc_java evidence "$1" >/dev/null
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
  _cdc_java baseline "$1" "$2" "$3" "$4" >/dev/null
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
  local baseline_file="${5:-}" exclusion_sql='' ids sql raw request rows baseline=null
  _cdc_validate_outbox_locator "$schema" "$aggregate_type" "$aggregate_id" || return 1
  if [[ -n "$baseline_file" ]]; then
    _cdc_validate_outbox_baseline "$baseline_file" "$schema" "$aggregate_type" "$aggregate_id" || return 1
    ids="$(_cdc_baseline_event_ids_sql "$baseline_file")" || return 1
    [[ -z "$ids" ]] || exclusion_sql=" AND event_id::text NOT IN (${ids})"
    baseline="$(cat "$baseline_file")" || return 1
  fi
  sql="SELECT event_id::text, topic, message_key, COALESCE(kafka_partition_id::text, 'NULL'), encode(payload, 'hex'), payload_type, round(extract(epoch from created_at) * 1000)::bigint, headers_json, aggregate_type, aggregate_id FROM ${schema}.outbox WHERE aggregate_type = '${aggregate_type}' AND aggregate_id = '${aggregate_id}'${exclusion_sql} ORDER BY created_at, event_id"
  raw="$(_cdc_outbox "$sql")" || { _cdc_fail 'failed to read durable outbox rows'; return 1; }
  rows="$(printf '%s\n' "$raw" | jq -Rsc 'split("\n") | map(select(length > 0) | split("\t") |
    {event_id:.[0],topic:.[1],message_key:.[2],explicit_partition:(if .[3] == "NULL" then null else (.[3]|tonumber) end),
     payload_hex:.[4],payload_type:.[5],created_at_unix_ms:(.[6]|tonumber),headers_json:.[7],aggregate_type:.[8],aggregate_id:.[9]})')" || return 1
  request="$(mktemp)" || return 1
  if ! jq -n --arg schema "$schema" --arg type "$aggregate_type" --arg identity "$aggregate_id" \
      --argjson rows "$rows" --argjson baseline "$baseline" \
      '{schema:$schema,aggregate_type:$type,aggregate_id:$identity,rows:$rows} +
       (if $baseline == null then {} else {baseline:$baseline} end)' >"$request"; then
    rm -f "$request"; return 1
  fi
  local status=0
  _cdc_java outbox-probe "$request" "$output" >/dev/null || status=$?
  rm -f "$request"
  return "$status"
}

cdc_assert_same_probe() {
  _cdc_java same-probe "$1" "$2" >/dev/null
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

cdc_assert_probe_publication() {
  local probe="$1" baseline_snapshot="$2" output="${3:-}"
  local topic event_id directory partition offset metadata headers key value status=0
  _cdc_validate_probe "$probe" || return 1
  topic="$(_cdc_probe_field "$probe" topic)" || return 1
  event_id="$(_cdc_probe_field "$probe" event_id)" || return 1
  directory="$(mktemp -d)" || return 1
  [[ -n "$output" ]] || output="$directory/publication.json"
  if ! _cdc_wait_for_event_after_snapshot "$topic" "$event_id" "$baseline_snapshot" "$directory/location"; then
    rm -rf -- "$directory"; return 1
  fi
  IFS=$'\t' read -r partition offset <"$directory/location"
  if ! metadata="$(_cdc_read_record_metadata "$topic" "$partition" "$offset")" ||
     ! headers="$(_cdc_read_record_headers "$topic" "$partition" "$offset")" ||
     ! key="$(_cdc_record_key_hex "$topic" "$partition" "$offset")" ||
     ! value="$(_cdc_record_value_hex "$topic" "$partition" "$offset")" ||
     ! cdc_capture_topic_end_offsets "$topic" "$directory/current"; then
    rm -rf -- "$directory"; return 1
  fi
  if ! jq -n --arg metadata "$metadata" --arg headers "$headers" --arg key "$key" --arg value "$value" \
      --arg separator "$CDC_VERIFIER_HEADER_SEPARATOR" --rawfile current "$directory/current" \
      '{metadata:$metadata,headers:$headers,key_console_hex:$key,value_console_hex:$value,
        header_separator:$separator,current_snapshot:$current}' >"$directory/wire.json"; then
    rm -rf -- "$directory"; return 1
  fi
  _cdc_java console-record "$probe" "$baseline_snapshot" "$directory/wire.json" "$output" || status=$?
  rm -rf -- "$directory"
  return "$status"
}
