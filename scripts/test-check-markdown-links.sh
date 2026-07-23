#!/usr/bin/env bash

set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
checker="$script_dir/check-markdown-links.sh"
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

mkdir -p "$fixture_root/valid"
printf '%s\n' '# Root' '' '[Child](child.md#event-driven-design)' '[External](https://example.com/docs)' >"$fixture_root/valid/README.md"
printf '%s\n' '# Child' '' '## Event-driven Design' >"$fixture_root/valid/child.md"
assert_succeeds 'a valid relative file and heading link' "$checker" "$fixture_root/valid/README.md"

mkdir -p "$fixture_root/missing-file"
printf '%s\n' '# Root' '' '[Missing](missing.md)' >"$fixture_root/missing-file/README.md"
assert_fails_with 'a missing local target' 'Missing target' "$checker" "$fixture_root/missing-file/README.md"

mkdir -p "$fixture_root/missing-heading"
printf '%s\n' '# Root' '' '[Child](child.md#missing)' >"$fixture_root/missing-heading/README.md"
printf '%s\n' '# Child' '' '## Present' >"$fixture_root/missing-heading/child.md"
assert_fails_with 'a missing local heading' 'Missing heading' "$checker" "$fixture_root/missing-heading/README.md"

assert_succeeds 'the repository README and documentation indexes by default' "$checker"

architecture_index="$repo_root/services/docs/architecture/README.md"
for architecture_specification in \
  system-boundaries.md \
  ordering-and-latency.md \
  eventing-and-cqrs.md \
  reliability-and-consistency.md; do
  if ! grep -Fq "($architecture_specification)" "$architecture_index"; then
    echo "Missing architecture index entry: $architecture_specification" >&2
    exit 1
  fi
done

assert_succeeds 'the architecture index and its canonical specifications' "$checker" "$architecture_index"

contracts_index="$repo_root/services/docs/contracts/README.md"
for contract_specification in kafka-events.md grpc-apis.md fix-gateway.md; do
  if ! grep -Fq "($contract_specification)" "$contracts_index"; then
    echo "Missing contracts index entry: $contract_specification" >&2
    exit 1
  fi
done

assert_succeeds 'the contracts index and its canonical specifications' "$checker" "$contracts_index"

platform_index="$repo_root/services/docs/platform/README.md"
for platform_specification in \
  data-model.md \
  database-architecture.md \
  configuration.md \
  development-environment.md \
  deployment.md \
  observability.md \
  testing.md \
  troubleshooting.md; do
  if ! grep -Fq "($platform_specification)" "$platform_index"; then
    echo "Missing platform index entry: $platform_specification" >&2
    exit 1
  fi
done

assert_succeeds 'the platform index and its canonical specifications' "$checker" "$platform_index"

target_docs_index="$repo_root/services/docs/README.md"
for service_documentation in \
  ../account-service/docs/README.md \
  ../risk-service/docs/README.md \
  ../quickfix-gateway/docs/README.md \
  ../persistence/docs/README.md; do
  if ! grep -Fq "($service_documentation)" "$target_docs_index"; then
    echo "Missing service documentation index entry: $service_documentation" >&2
    exit 1
  fi
done

assert_succeeds 'the target documentation index and service-owned specifications' "$checker" "$target_docs_index"

echo 'Markdown link checks passed.'
