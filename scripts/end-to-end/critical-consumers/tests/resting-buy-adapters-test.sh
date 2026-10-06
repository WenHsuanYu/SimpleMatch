#!/usr/bin/env bash
set -euo pipefail
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/end-to-end/critical-consumers/lib/test-interfaces.sh
source "$script_dir/../lib/test-interfaces.sh"

gateway_env_modified=false
restoration_failed=false
timeout_seconds=1
kns() {
  # Test the external kubectl arguments, without printing the operator token.
  case "$1" in
    get) return 0 ;;
    set)
      case " $* " in
        *'SIMPLEMATCH_QUICKFIX_GATEWAY_OPERATIONS_AUTOMATIC_CLOSE_ENABLED=true'*) [[ "$expected_close" == true ]] ;;
        *'SIMPLEMATCH_QUICKFIX_GATEWAY_OPERATIONS_AUTOMATIC_CLOSE_ENABLED=false'*) [[ "$expected_close" == false ]] ;;
        *) return 0 ;;
      esac ;;
    rollout) return 0 ;;
    *) return 1 ;;
  esac
}
die() { printf '%s\n' "$*" >&2; exit 1; }

expected_close=true
enable_gateway_operations true
[[ "$gateway_env_modified" == true && -n "$gateway_operator_token" ]]
restore_gateway_environment
[[ "$gateway_env_modified" == false && "$restoration_failed" == false ]]
expected_close=false
enable_gateway_operations
restore_gateway_environment
printf '%s\n' 'Normal flow preserves automatic close; legacy fault scenarios retain their explicit override.'

temporary_directory="$(mktemp -d /tmp/simplematch-live-open.XXXXXX)"
trap 'rm -rf -- "$temporary_directory"' EXIT
timeout_seconds=3
attempts=0
mode=open
test_epoch=0
date() { printf '%s\n' "$test_epoch"; }
sleep() { test_epoch=$((test_epoch + 1)); }
gateway_request() {
  local method="$1" destination="$3" fixture
  if [[ "$method" == POST ]]; then
    [[ "$mode" == open ]] || die 'status observation must not reopen Gateway'
    attempts=$((attempts + 1))
    fixture=waiting
    (( attempts < 3 )) || fixture=opened
  elif [[ "$mode" == observe ]]; then
    attempts=$((attempts + 1))
    fixture=unready-open
    (( attempts < 3 )) || fixture=opened
  elif [[ "$mode" == paused ]]; then
    fixture=waiting
  else
    fixture=eligible
  fi
  cp "$script_dir/fixtures/live-gateway/$fixture.json" "$destination"
}
open_gateway_from_live_observations request.json "$temporary_directory/before.json" "$temporary_directory/open.json"
[[ "$attempts" == 3 ]]
mode=observe
attempts=0
wait_gateway_live_open "$temporary_directory/after.json"
[[ "$attempts" == 3 ]]
mode=paused
timeout_seconds=2
if wait_gateway_live_open "$temporary_directory/timeout.json"; then
  die 'a paused Gateway must not be treated as OPEN after the deadline'
fi
printf '%s\n' 'Live Gateway retries real operator open; final observation never reopens a paused gate.'
