#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/lib/cdc-observer-fixture.sh
source "$script_dir/lib/cdc-observer-fixture.sh"

command -v jq >/dev/null 2>&1 || {
  printf '%s\n' 'jq is required.' >&2
  exit 1
}

event_id='00000000-0000-7000-8000-000000000001'
headers_json="$(cdc_observer_headers_json "$event_id")"

jq -e \
  --arg event_id "$event_id" \
  --arg payload_type "$(cdc_observer_payload_type)" \
  --arg content_type "$(cdc_observer_content_type)" \
  '
    type == "object" and
    .event_id == $event_id and
    .content_type == $content_type and
    .payload_type == $payload_type and
    (keys | length) == 3
  ' <<<"$headers_json" >/dev/null || {
  printf '%s\n' 'CDC observer fixture headers must match the outbox header contract.' >&2
  exit 1
}

[[ "$headers_json" != '{}' ]] || {
  printf '%s\n' 'CDC observer fixture must not publish empty headers_json.' >&2
  exit 1
}

printf '%s\n' 'CDC observer fixture header contract is valid.'
