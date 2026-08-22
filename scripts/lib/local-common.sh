#!/usr/bin/env bash

# Shared helpers for SimpleMatch local-lab shell scripts.
# Callers should set SIMPLEMATCH_DRY_RUN=true when commands must only be printed.

simplematch_log() {
  printf '\n=== %s ===\n' "$*"
}

simplematch_info() {
  printf '%s\n' "$*"
}

simplematch_warn() {
  printf 'WARNING: %s\n' "$*" >&2
}

simplematch_die() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

simplematch_quote_command() {
  printf '$'
  printf ' %q' "$@"
  printf '\n'
}

simplematch_run() {
  if [[ "${SIMPLEMATCH_DRY_RUN:-false}" == true ]]; then
    simplematch_quote_command "$@"
    return 0
  fi
  "$@"
}

simplematch_run_best_effort() {
  if [[ "${SIMPLEMATCH_DRY_RUN:-false}" == true ]]; then
    simplematch_quote_command "$@"
    return 0
  fi
  "$@" || true
}

simplematch_require_command() {
  local command_name="$1"
  command -v "$command_name" >/dev/null 2>&1 ||
    simplematch_die "$command_name is required"
}

simplematch_contains() {
  local needle="$1"
  shift
  local item
  for item in "$@"; do
    [[ "$item" == "$needle" ]] && return 0
  done
  return 1
}

simplematch_append_unique() {
  local array_name="$1"
  local value="$2"
  local -n target="$array_name"
  simplematch_contains "$value" "${target[@]:-}" || target+=("$value")
}
