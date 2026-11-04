# shellcheck shell=bash

matching_e2e_runtime_caught_up() {
  local metrics_json="$1"
  local expected_end_offset="$2"

  jq -e --argjson end_offset "$expected_end_offset" '
    .runtime_state == "READY"
    and .pending_inputs == 0
    and .pending_publications == 0
    and (
      ((.next_commit_offset | type) == "number"
        and .next_commit_offset >= $end_offset)
      or
      (.next_commit_offset == null
        and (.highest_contiguous_completed_offset | type) == "number"
        and .highest_contiguous_completed_offset >= ($end_offset - 1))
    )
  ' <<<"$metrics_json" >/dev/null
}
