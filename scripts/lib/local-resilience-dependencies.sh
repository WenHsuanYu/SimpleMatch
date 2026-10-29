#!/usr/bin/env bash

# Adapter for the Java dependency recovery verifier.
# The collector retains live operations and Redis marker interpretation.

set -euo pipefail

resilience_dependency_valid_component() {
  case "$1" in
    postgresql|redis|kafka) return 0 ;;
    *) return 1 ;;
  esac
}

resilience_dependency_redis_marker_state() {
  local marker_response="$1"
  local expected_value="$2"

  [[ -n "$expected_value" ]] || return 1
  case "$marker_response" in
    "$expected_value") printf 'present\n' ;;
    ''|'(nil)') printf 'absent\n' ;;
    *) return 1 ;;
  esac
}

resilience_dependency_report_is_passed() {
  resilience_verify_evidence dependency "$1" "$2" >/dev/null
}
