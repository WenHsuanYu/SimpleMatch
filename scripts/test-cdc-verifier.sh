#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/lib/cdc-verifier.sh
source "$SCRIPT_DIR/lib/cdc-verifier.sh"

fail() {
  printf 'test-cdc-verifier: %s\n' "$*" >&2
  exit 1
}

assert_equal() {
  local actual="$1" expected="$2" description="$3"
  [[ "$actual" == "$expected" ]] || fail "$description: expected '$expected', got '$actual'"
}

assert_failure_contains() {
  local expected="$1" stderr_file="$2"
  shift 2
  if "$@" 2>"$stderr_file"; then
    fail "expected failure containing '$expected'"
  fi
  grep -F "$expected" "$stderr_file" >/dev/null \
    || fail "failure did not contain '$expected': $(cat "$stderr_file")"
}

assert_file_contains() {
  local expected="$1" file="$2"
  grep -F "$expected" "$file" >/dev/null \
    || fail "file did not contain '$expected': $(cat "$file")"
}

assert_file_not_contains() {
  local unexpected="$1" file="$2"
  grep -F "$unexpected" "$file" >/dev/null \
    && fail "file unexpectedly contained sensitive text '$unexpected': $(cat "$file")"
  return 0
}

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

MOCK_OFFSET_CALL_FILE="$TMP_DIR/offset-call"
MOCK_TARGET_EVENT='00000000-0000-0000-0000-000000000042'
MOCK_OUTBOX_PAYLOAD='account-payload-v1'
MOCK_KAFKA_PAYLOAD='account-payload-v1'
MOCK_KEY='account-42'
MOCK_TIMESTAMP='2000'
MOCK_RUN_ID='11111111-2222-4333-8444-555555555555'
MOCK_PAYLOAD_TYPE='account.v1'
MOCK_EXTRA_HEADER=''
MOCK_CONNECTOR_STATE='RUNNING'
MOCK_MODE='locator'

reset_mock() {
  MOCK_MODE="$1"
  MOCK_OUTBOX_PAYLOAD='account-payload-v1'
  MOCK_KAFKA_PAYLOAD='account-payload-v1'
  MOCK_KEY='account-42'
  MOCK_TIMESTAMP='2000'
  MOCK_RUN_ID='11111111-2222-4333-8444-555555555555'
  MOCK_PAYLOAD_TYPE='account.v1'
  MOCK_EXTRA_HEADER=''
  MOCK_CONNECTOR_STATE='RUNNING'
  printf '0\n' >"$MOCK_OFFSET_CALL_FILE"
}

mock_outbox_exec() {
  local sql="$1" payload_hex
  [[ "$sql" == *'account_service.outbox'* ]] \
    || fail "outbox adapter did not receive owning table query: $sql"
  [[ "$sql" == *"aggregate_type = 'account_reservation'"* ]] \
    || fail "outbox adapter did not receive aggregate type: $sql"
  [[ "$sql" == *"aggregate_id = 'reservation-42'"* ]] \
    || fail "outbox adapter did not receive business identity: $sql"
  payload_hex="$(printf '%s' "$MOCK_OUTBOX_PAYLOAD" | od -An -tx1 | tr -d ' \n')"
  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
    "$MOCK_TARGET_EVENT" \
    'account.lifecycle' \
    'account-42' \
    'NULL' \
    "$payload_hex" \
    "$MOCK_PAYLOAD_TYPE" \
    '2000' \
    '{"trace-id":"account-42"}' \
    'account_reservation' \
    'reservation-42'
}

mock_connect_status_exec() {
  local connector_name="$1"
  [[ "$connector_name" == 'account-service-outbox' ]] \
    || fail "unexpected connector status lookup: $connector_name"
  printf '{"connector":{"state":"%s"},"tasks":[{"id":0,"state":"%s"}]}\n' \
    "$MOCK_CONNECTOR_STATE" "$MOCK_CONNECTOR_STATE"
}

mock_kafka_exec() {
  local command="$1"
  shift
  case "$command" in
    /opt/kafka/bin/kafka-get-offsets.sh)
      local mock_offset_call
      mock_offset_call="$(cat "$MOCK_OFFSET_CALL_FILE")"
      mock_offset_call=$((mock_offset_call + 1))
      printf '%s\n' "$mock_offset_call" >"$MOCK_OFFSET_CALL_FILE"
      case "$MOCK_MODE" in
        locator)
          if (( mock_offset_call == 1 )); then
            printf '%s\n' 'account.lifecycle:0:5' 'account.lifecycle:1:2'
          elif (( mock_offset_call == 2 )); then
            printf '%s\n' 'account.lifecycle:0:6' 'account.lifecycle:1:2'
          else
            printf '%s\n' 'account.lifecycle:0:6' 'account.lifecycle:1:3'
          fi
          ;;
        partition-drift)
          if (( mock_offset_call == 1 )); then
            printf '%s\n' 'account.lifecycle:0:5' 'account.lifecycle:1:2'
          else
            printf '%s\n' 'account.lifecycle:0:5' 'account.lifecycle:1:2' 'account.lifecycle:2:1'
          fi
          ;;
        scan-failure)
          if (( mock_offset_call == 1 )); then
            printf '%s\n' 'account.lifecycle:0:5'
          else
            printf '%s\n' 'account.lifecycle:0:6'
          fi
          ;;
        *)
          fail "unexpected mock mode: $MOCK_MODE"
          ;;
      esac
      ;;
    /opt/kafka/bin/kafka-console-consumer.sh)
      local partition='' offset='' max_messages=''
      local print_value=false print_key=false print_partition=false print_offset=false
      local print_timestamp=false print_headers=false arg
      while [[ $# -gt 0 ]]; do
        arg="$1"
        case "$arg" in
          --partition) partition="$2"; shift 2 ;;
          --offset) offset="$2"; shift 2 ;;
          --max-messages) max_messages="$2"; shift 2 ;;
          --property|--formatter-property)
            case "$2" in
              print.value=true) print_value=true ;;
              print.key=true) print_key=true ;;
              print.partition=true) print_partition=true ;;
              print.offset=true) print_offset=true ;;
              print.timestamp=true) print_timestamp=true ;;
              print.headers=true) print_headers=true ;;
            esac
            shift 2
            ;;
          *) shift ;;
        esac
      done

      if [[ "$MOCK_MODE" == partition-drift ]]; then
        fail 'partition-set drift must be rejected before scanning records'
      fi
      if [[ "$MOCK_MODE" == scan-failure ]]; then
        printf '%s\n' 'mock broker read failed' >&2
        return 73
      fi

      if [[ "$print_value" == true ]]; then
        [[ "$partition" == 1 && "$offset" == 2 && "$max_messages" == 1 ]] \
          || fail "payload read used unexpected location $partition:$offset max=$max_messages"
        printf '%s\n' "$MOCK_KAFKA_PAYLOAD"
        return
      fi
      if [[ "$print_key" == true && "$print_partition" == false && "$print_offset" == false && "$print_timestamp" == false && "$print_headers" == false ]]; then
        [[ "$partition" == 1 && "$offset" == 2 && "$max_messages" == 1 ]] \
          || fail "key read used unexpected location $partition:$offset max=$max_messages"
        printf '%s\n' "$MOCK_KEY"
        return
      fi
      if [[ "$print_headers" == true && "$print_key" == false && "$print_partition" == false && "$print_offset" == false && "$print_timestamp" == false ]]; then
        [[ "$partition" == 1 && "$offset" == 2 && "$max_messages" == 1 ]] \
          || fail "header read used unexpected location $partition:$offset max=$max_messages"
        printf 'id:%s%seventType:%s%sheaders_json:{"trace-id":"account-42"}%s__debezium.context.connectorLogicalName:simplematch-account-service%s__debezium.context.taskId:0%s__debezium.context.connectorName:postgresql%s__debezium.context.runId:%s' \
          "$MOCK_TARGET_EVENT" \
          "$CDC_VERIFIER_HEADER_SEPARATOR" \
          "$MOCK_PAYLOAD_TYPE" \
          "$CDC_VERIFIER_HEADER_SEPARATOR" \
          "$CDC_VERIFIER_HEADER_SEPARATOR" \
          "$CDC_VERIFIER_HEADER_SEPARATOR" \
          "$CDC_VERIFIER_HEADER_SEPARATOR" \
          "$CDC_VERIFIER_HEADER_SEPARATOR" \
          "$MOCK_RUN_ID"
        if [[ -n "$MOCK_EXTRA_HEADER" ]]; then
          printf '%s%s' "$CDC_VERIFIER_HEADER_SEPARATOR" "$MOCK_EXTRA_HEADER"
        fi
        printf '\n'
        return
      fi
      if [[ "$print_timestamp" == true && "$print_partition" == true && "$print_offset" == true && "$print_headers" == false && "$print_key" == false ]]; then
        [[ "$partition" == 1 && "$offset" == 2 && "$max_messages" == 1 ]] \
          || fail "metadata read used unexpected location $partition:$offset max=$max_messages"
        printf 'CreateTime:%s\tPartition:1\tOffset:2\n' "$MOCK_TIMESTAMP"
        return
      fi

      if [[ "$partition" == 0 && "$offset" == 5 && "$max_messages" == 1 ]]; then
        printf 'Partition:0\tOffset:5\tid:00000000-0000-0000-0000-000000000041%sheaders_json:{"trace-id":"other"}\n' \
          "$CDC_VERIFIER_HEADER_SEPARATOR"
      elif [[ "$partition" == 1 && "$offset" == 2 && "$max_messages" == 1 ]]; then
        printf 'Partition:1\tOffset:2\tid:%s%sheaders_json:{"trace-id":"account-42"}\n' \
          "$MOCK_TARGET_EVENT" "$CDC_VERIFIER_HEADER_SEPARATOR"
      fi
      ;;
    *)
      fail "unexpected Kafka command: $command"
      ;;
  esac
}

CDC_OUTBOX_EXEC=(mock_outbox_exec)
CDC_CONNECT_STATUS_EXEC=(mock_connect_status_exec)
CDC_KAFKA_EXEC=(mock_kafka_exec)
CDC_VERIFIER_POLL_INTERVAL_SECONDS=0
CDC_VERIFIER_SCAN_TIMEOUT_MS=10
CDC_VERIFIER_TIMEOUT_SECONDS=2

reset_mock locator
probe="$TMP_DIR/account-probe.json"
cdc_read_outbox_probe account_service account_reservation reservation-42 "$probe"
assert_equal "$(jq -r '.event_id' "$probe")" "$MOCK_TARGET_EVENT" 'probe event identity'
assert_equal "$(jq -r '.reservation_id' "$probe")" 'reservation-42' 'probe reservation identity'
assert_equal "$(jq -r '.account_id' "$probe")" 'account-42' 'probe account identity'
assert_equal "$(jq -r '.message_key' "$probe")" 'account-42' 'probe message key'
assert_equal "$(jq -r '.topic' "$probe")" 'account.lifecycle' 'probe topic'
assert_equal "$(jq -r '.payload_sha256' "$probe")" \
  'c09d6057ae3b359e92ee548380b7ad736e39b62c439e8ce8d9fd83ac7d2a3791' \
  'probe payload digest'
assert_equal "$(jq -r '.created_at_unix_ms' "$probe")" '2000' 'probe timestamp'
assert_equal "$(jq -r '.payload_type' "$probe")" 'account.v1' 'probe payload type'
assert_equal "$(jq -r '.explicit_partition' "$probe")" 'null' 'probe nullable partition'

cdc_wait_for_connector_state account-service-outbox RUNNING

baseline="$TMP_DIR/baseline.tsv"
cdc_capture_topic_end_offsets account.lifecycle "$baseline"
assert_equal "$(cat "$baseline")" $'0\t5\n1\t2' 'baseline offset snapshot'
location="$(cdc_assert_probe_publication "$probe" "$baseline")"
assert_equal "$location" $'1\t2' 'probe must locate the exact Debezium event'

second_probe="$TMP_DIR/account-probe-after-recovery.json"
cdc_read_outbox_probe account_service account_reservation reservation-42 "$second_probe"
cdc_assert_same_probe "$probe" "$second_probe"

reset_mock locator
key_baseline="$TMP_DIR/key-baseline.tsv"
key_error="$TMP_DIR/key-error.log"
cdc_capture_topic_end_offsets account.lifecycle "$key_baseline"
MOCK_KEY='wrong-key'
assert_failure_contains "$MOCK_TARGET_EVENT" "$key_error" \
  cdc_assert_probe_publication "$probe" "$key_baseline"
assert_file_contains 'key bytes mismatch' "$key_error"

reset_mock locator
timestamp_baseline="$TMP_DIR/timestamp-baseline.tsv"
timestamp_error="$TMP_DIR/timestamp-error.log"
cdc_capture_topic_end_offsets account.lifecycle "$timestamp_baseline"
MOCK_TIMESTAMP='20001'
assert_failure_contains 'timestamp mismatch' "$timestamp_error" \
  cdc_assert_probe_publication "$probe" "$timestamp_baseline"

reset_mock locator
run_id_baseline="$TMP_DIR/run-id-baseline.tsv"
run_id_error="$TMP_DIR/run-id-error.log"
cdc_capture_topic_end_offsets account.lifecycle "$run_id_baseline"
MOCK_RUN_ID='not-a-uuid'
assert_failure_contains 'non-UUID Debezium runId' "$run_id_error" \
  cdc_assert_probe_publication "$probe" "$run_id_baseline"

reset_mock locator
header_baseline="$TMP_DIR/header-baseline.tsv"
header_error="$TMP_DIR/header-error.log"
cdc_capture_topic_end_offsets account.lifecycle "$header_baseline"
MOCK_EXTRA_HEADER='unexpected:header'
assert_failure_contains 'unexpected header' "$header_error" \
  cdc_assert_probe_publication "$probe" "$header_baseline"

reset_mock locator
payload_baseline="$TMP_DIR/payload-baseline.tsv"
payload_error="$TMP_DIR/payload-error.log"
cdc_capture_topic_end_offsets account.lifecycle "$payload_baseline"
MOCK_KAFKA_PAYLOAD='sensitive-observed-account-payload'
assert_failure_contains "$MOCK_TARGET_EVENT" "$payload_error" \
  cdc_assert_probe_publication "$probe" "$payload_baseline"
assert_file_contains 'payload mismatch' "$payload_error"
assert_file_not_contains "$MOCK_OUTBOX_PAYLOAD" "$payload_error"
assert_file_not_contains "$MOCK_KAFKA_PAYLOAD" "$payload_error"
assert_file_not_contains "$(printf '%s' "$MOCK_OUTBOX_PAYLOAD" | od -An -tx1 | tr -d ' \n')" "$payload_error"
assert_file_not_contains "$(printf '%s' "$MOCK_KAFKA_PAYLOAD" | od -An -tx1 | tr -d ' \n')" "$payload_error"

reset_mock partition-drift
partition_baseline="$TMP_DIR/partition-baseline.tsv"
partition_error="$TMP_DIR/partition-error.log"
cdc_capture_topic_end_offsets account.lifecycle "$partition_baseline"
assert_failure_contains 'partition-set drift' "$partition_error" \
  cdc_assert_probe_publication "$probe" "$partition_baseline"

reset_mock scan-failure
failure_baseline="$TMP_DIR/failure-baseline.tsv"
failure_error="$TMP_DIR/failure-error.log"
cdc_capture_topic_end_offsets account.lifecycle "$failure_baseline"
assert_failure_contains 'failed to scan Kafka window' "$failure_error" \
  cdc_assert_probe_publication "$probe" "$failure_baseline"

printf '%s\n' 'CDC verifier interface tests passed.'
