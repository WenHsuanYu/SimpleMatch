#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/lib/matching-e2e.sh
source "$script_dir/lib/matching-e2e.sh"

pending_commit='{"runtime_state":"READY","pending_inputs":0,"pending_publications":0,"highest_contiguous_completed_offset":16,"next_commit_offset":17}'
acknowledged_commit='{"runtime_state":"READY","pending_inputs":0,"pending_publications":0,"highest_contiguous_completed_offset":16,"next_commit_offset":null}'
incomplete='{"runtime_state":"READY","pending_inputs":0,"pending_publications":0,"highest_contiguous_completed_offset":15,"next_commit_offset":null}'
pending_publications='{"runtime_state":"READY","pending_inputs":0,"pending_publications":1,"highest_contiguous_completed_offset":16,"next_commit_offset":17}'

matching_e2e_runtime_caught_up "$pending_commit" 17
matching_e2e_runtime_caught_up "$acknowledged_commit" 17
if matching_e2e_runtime_caught_up "$incomplete" 17; then
  printf '%s\n' 'Incomplete offset range unexpectedly passed.' >&2
  exit 1
fi
if matching_e2e_runtime_caught_up "$pending_publications" 17; then
  printf '%s\n' 'Pending publications unexpectedly passed.' >&2
  exit 1
fi

fixture_dir="$(mktemp -d)"
trap 'rm -rf "$fixture_dir"' EXIT
runtime_evidence="$(jq -cn '[range(0; 15) | {pod:("matching-" + tostring),uid:("uid-" + tostring),node:"worker-a"}]')"
ring_evidence="$(jq -cn '[range(0; 15) | {runtime_state:"READY",input_ring:{capacity:1024,occupancy:0},output_ring:{capacity:2048,occupancy:0}}]')"
valid_report="$fixture_dir/valid.json"
jq -n --argjson rings "$ring_evidence" '
  {status:"PASSED",kafka_e2e_latency_ns:{p50:1,p99:2,"p99_9":3,max:4},
   kafka_e2e_latency_definition:"per-event helper observation",
   ring_occupancy:{before:$rings,after:$rings},loss:0,duplicates:0,
   replay_lag_seconds:1,replacement_seconds:2,command_end_offset:17,
   fault_mode:"pod-delete",
   target:{old_uid:"old",new_uid:"new",old_node:"worker-a",new_node:"worker-a",
           pvc:"matching-baseline-matching-0",pv:"pv-0",old_restart_count:0,new_restart_count:0},
   evidence:{e2e_before:"e2e-before.json",e2e_after:"e2e-after.json"}}' >"$valid_report"
matching_e2e_report_is_valid "$valid_report" "$runtime_evidence"

invalid_node_report="$fixture_dir/invalid-node.json"
jq '.target.new_node = "worker-b"' "$valid_report" >"$invalid_node_report"
if matching_e2e_report_is_valid "$invalid_node_report" "$runtime_evidence"; then
  printf '%s\n' 'Cross-node replacement unexpectedly passed.' >&2
  exit 1
fi

incomplete_fleet_report="$fixture_dir/incomplete-fleet.json"
jq '.ring_occupancy.after = .ring_occupancy.after[0:14]' "$valid_report" >"$incomplete_fleet_report"
if matching_e2e_report_is_valid "$incomplete_fleet_report" "$runtime_evidence"; then
  printf '%s\n' 'Incomplete fleet evidence unexpectedly passed.' >&2
  exit 1
fi

printf '%s\n' 'Matching deployed E2E contract tests passed.'
