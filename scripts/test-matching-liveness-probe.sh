#!/usr/bin/env bash
set -euo pipefail

binary="${1:?usage: test-matching-liveness-probe.sh MATCHING_BINARY}"
test_dir="$(mktemp -d /tmp/simplematch-matching-probe.XXXXXX)"
trap 'rm -rf "$test_dir"' EXIT

status_path="$test_dir/runtime-status"
metrics_path="$test_dir/runtime-metrics.json"
printf 'READY\n' >"$status_path"
printf '{"runtime_state":"READY"}\n' >"$metrics_path"

# A stable READY state may have an old state file; the metrics file is the
# continuously refreshed process heartbeat.
touch -d '2 minutes ago' "$status_path"
MATCHING_STATUS_PATH="$status_path" MATCHING_METRICS_PATH="$metrics_path" \
  "$binary" liveness

touch -d '2 minutes ago' "$metrics_path"
if MATCHING_STATUS_PATH="$status_path" MATCHING_METRICS_PATH="$metrics_path" \
  "$binary" liveness; then
  echo 'stale Matching metrics heartbeat must fail liveness' >&2
  exit 1
fi

touch "$metrics_path"
printf 'FAILED\n' >"$status_path"
if MATCHING_STATUS_PATH="$status_path" MATCHING_METRICS_PATH="$metrics_path" \
  "$binary" liveness; then
  echo 'terminal Matching status must fail liveness' >&2
  exit 1
fi

echo 'Matching liveness probe checks the metrics heartbeat.'
