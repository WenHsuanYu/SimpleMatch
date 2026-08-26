#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
production_runner="$script_dir/run-local-production-like-certification.sh"
failure_runner="$script_dir/end-to-end/critical-consumers/run-failure-certification.sh"
cluster_module="$script_dir/end-to-end/critical-consumers/lib/cluster-data.sh"

fail() {
  printf 'Local certification trading-day contract: %s\n' "$*" >&2
  exit 1
}

bash -n "$production_runner"
bash -n "$failure_runner"
bash -n "$cluster_module"

grep -Fq \
  'certification_trading_day="${SIMPLEMATCH_CERTIFICATION_TRADING_DAY:-$(TZ=Asia/Taipei date +%F)}"' \
  "$production_runner" ||
  fail 'default trading day must use the production Asia/Taipei calendar date'

if grep -Fq 'certification_trading_day="${SIMPLEMATCH_CERTIFICATION_TRADING_DAY:-$(date -u +%F)}"' \
    "$production_runner"; then
  fail 'default trading day must not use the UTC calendar date'
fi

grep -Fq 'current_taipei_calendar_day()' "$cluster_module" ||
  fail 'failure certification is missing the Asia/Taipei date source'
grep -Fq 'require_live_fix_trading_day "$trading_day"' "$cluster_module" ||
  fail 'failure certification must reject stale retained namespaces during input preparation'

pre_send_guard_line="$(
  grep -nF 'require_live_fix_trading_day "$trading_day"' "$failure_runner" |
    cut -d: -f1
)"
release_line="$(
  grep -nF 'release_fix_submit_client' "$failure_runner" |
    tail -1 |
    cut -d: -f1
)"
[[ "$pre_send_guard_line" =~ ^[0-9]+$ && "$release_line" =~ ^[0-9]+$ ]] ||
  fail 'failure certification trading-day guard or FIX release is missing'
(( pre_send_guard_line + 1 == release_line )) ||
  fail 'failure certification must recheck the trading day immediately before FIX release'

# At 17:00 UTC the Taiwan calendar has already advanced to the next day.
# This is the deterministic window that previously produced a Risk routing
# rejection even in a freshly created production-like namespace.
boundary_epoch="$(date -u -d '2026-08-26 17:00:00Z' +%s)"
utc_day="$(date -u -d "@$boundary_epoch" +%F)"
taipei_day="$(TZ=Asia/Taipei date -d "@$boundary_epoch" +%F)"

[[ "$utc_day" == '2026-08-26' ]] ||
  fail "unexpected UTC fixture date: $utc_day"
[[ "$taipei_day" == '2026-08-27' ]] ||
  fail "unexpected Asia/Taipei fixture date: $taipei_day"
[[ "$utc_day" != "$taipei_day" ]] ||
  fail 'boundary fixture must prove UTC and Asia/Taipei calendar dates differ'

printf '%s\n' 'Local certification trading-day contract is valid.'
