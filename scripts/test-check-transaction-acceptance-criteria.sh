#!/usr/bin/env bash

set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
checker="$script_dir/check-transaction-acceptance-criteria.sh"
repo_root="$(cd -- "$script_dir/.." && pwd)"
fixture_root="$(mktemp -d)"

cleanup() {
  rm -rf "$fixture_root"
}
trap cleanup EXIT

assert_succeeds() {
  local description="$1"
  shift

  if ! "$@" >"$fixture_root/output" 2>&1; then
    echo "Expected success: $description" >&2
    cat "$fixture_root/output" >&2
    exit 1
  fi
}

assert_fails_with() {
  local description="$1"
  local expected_message="$2"
  shift 2

  if "$@" >"$fixture_root/output" 2>&1; then
    echo "Expected failure: $description" >&2
    exit 1
  fi

  if ! grep -Fq "$expected_message" "$fixture_root/output"; then
    echo "Missing expected message for: $description" >&2
    cat "$fixture_root/output" >&2
    exit 1
  fi
}

assert_succeeds 'the repository refactoring plan' bash "$checker"

broken_plan="$fixture_root/taiwan-event-driven-refactor-plan.md"
cp "$repo_root/docs/taiwan-event-driven-refactor-plan.md" "$broken_plan"
sed -i '/^##### Timeout policy$/d' "$broken_plan"
assert_fails_with 'a missing required transaction field' 'Missing transaction acceptance field' \
  bash "$checker" --plan "$broken_plan"

echo 'Transaction acceptance criteria checker tests passed.'
