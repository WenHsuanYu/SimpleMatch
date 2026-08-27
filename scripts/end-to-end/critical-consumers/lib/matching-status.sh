#!/usr/bin/env bash

# Pure validation and normalization for Matching readiness evidence.
# This module performs no Kubernetes or Kafka I/O.

matching_runtime_default_max_age_millis=5000

matching_runtime_readiness_reason() {
  local now_epoch_millis="${1:-$(date +%s%3N)}"
  local maximum_age_millis="${2:-$matching_runtime_default_max_age_millis}"

  [[ "$now_epoch_millis" =~ ^[0-9]+$ ]] || {
    printf '%s\n' 'INVALID_VALIDATION_TIME'
    return 1
  }
  [[ "$maximum_age_millis" =~ ^[0-9]+$ ]] || {
    printf '%s\n' 'INVALID_FRESHNESS_LIMIT'
    return 1
  }

  jq -r \
    --argjson nowEpochMillis "$now_epoch_millis" \
    --argjson maximumAgeMillis "$maximum_age_millis" '
      if .schema_version != 1 then "INVALID_SCHEMA"
      elif .runtime_state != "READY" then "RUNTIME_NOT_READY"
      elif .partition_state != "OPEN" then "PARTITION_NOT_OPEN"
      elif ((.updated_at_epoch_ms | type) != "number") or .updated_at_epoch_ms < 0 then
        "TIMESTAMP_MISSING"
      elif .updated_at_epoch_ms > $nowEpochMillis then "TIMESTAMP_IN_FUTURE"
      elif (($nowEpochMillis - .updated_at_epoch_ms) > $maximumAgeMillis) then "STATUS_STALE"
      else "READY"
      end
    '
}

matching_runtime_is_ready() {
  [[ "$(matching_runtime_readiness_reason "$@")" == "READY" ]]
}

matching_runtime_observed_at() {
  local metrics_file="$1"
  local epoch_millis
  local seconds
  local millis

  epoch_millis="$(jq -er '.updated_at_epoch_ms | select(type == "number") | floor' "$metrics_file")" ||
    return 1
  [[ "$epoch_millis" =~ ^[0-9]+$ ]] || return 1

  seconds="$((epoch_millis / 1000))"
  millis="$((epoch_millis % 1000))"
  date -u -d "@${seconds}.$(printf '%03d' "$millis")" '+%Y-%m-%dT%H:%M:%S.%3NZ'
}

normalize_matching_committed_offsets() {
  local normalized_rows
  normalized_rows="$(
    awk '
      $1 ~ /^matching-partition-consumer-[0-9]+$/ &&
      $2 == "matching.commands" {
        if ($3 !~ /^[0-9]+$/ || $4 !~ /^[0-9]+$/) {
          exit 2
        }
        expected_group = "matching-partition-consumer-" $3
        if ($1 != expected_group) {
          exit 3
        }
        print $3 "\t" $4
      }
    '
  )" || return 1

  jq -eRn --arg rows "$normalized_rows" '
    ($rows | split("\n") | map(select(length > 0))) as $lines
    | [
        $lines[]
        | split("\t")
        | {
            partition:(.[0] | tonumber),
            committedOffset:(.[1] | tonumber)
          }
      ]
    | sort_by(.partition) as $partitions
    | if ($partitions | group_by(.partition) | all(.[]; length == 1)) then
        {topic:"matching.commands", partitions:$partitions}
      else
        error("duplicate Matching committed position")
      end
  '
}

matching_committed_offset_for_partition() {
  local snapshot="$1"
  local partition="$2"

  jq -er --argjson partition "$partition" '
    [.partitions[] | select(.partition == $partition)] as $matches
    | if ($matches | length) == 1 then
        $matches[0].committedOffset
      else
        error("Matching committed position must exist exactly once")
      end
    | select(type == "number" and . >= 0)
  ' "$snapshot"
}
