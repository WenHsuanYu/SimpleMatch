#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/lib/local-resilience.sh
source "$script_dir/lib/local-resilience.sh"

safe_log="$(mktemp "${TMPDIR:-/tmp}/simplematch-resilience-safe.XXXXXX.log")"
unsafe_log="$(mktemp "${TMPDIR:-/tmp}/simplematch-resilience-unsafe.XXXXXX.log")"
trap 'rm -f "$safe_log" "$unsafe_log"' EXIT

printf '%s\n' 'event=consumer_recovered correlation_id=abc-123' >"$safe_log"
resilience_log_is_safe "$safe_log"
for sensitive in \
    'fromApp session=FIX.4.4 msg=8=FIX.4.4|35=D|55=2330' \
    '{"event":"connection","password":"secret-value"}' \
    '{"event":"connection","token":"token-value"}' \
    '{"event":"connection","privateKey":"key-material"}' \
    'authorization=Bearer eyJhbGciOiJIUzI1NiJ9.safe-token'; do
  printf '%s\n' "$sensitive" >"$unsafe_log"
  if resilience_log_is_safe "$unsafe_log"; then exit 1; fi
done

printf '%s\n' 'Local resilience sensitive-log contracts passed.'
