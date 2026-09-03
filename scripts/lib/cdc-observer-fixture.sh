#!/usr/bin/env bash

# Shared constants for the local Risk CDC observer fixture. Keeping the
# fixture's transport headers in one small module lets the preflight contract
# and the runtime observer exercise the same value-producing seam.

cdc_observer_payload_type() {
  printf '%s\n' 'simplematch.matching.runtime.v1.MatchingCommand'
}

cdc_observer_content_type() {
  printf '%s\n' 'application/x-protobuf'
}

cdc_observer_headers_json() {
  local event_id="$1"

  [[ "$event_id" =~ ^[0-9a-fA-F-]{36}$ ]] || return 1
  jq -cn \
    --arg event_id "$event_id" \
    --arg content_type "$(cdc_observer_content_type)" \
    --arg payload_type "$(cdc_observer_payload_type)" \
    '{event_id:$event_id,content_type:$content_type,payload_type:$payload_type}'
}
