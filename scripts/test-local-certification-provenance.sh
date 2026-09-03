#!/usr/bin/env bash
set -Eeuo pipefail
IFS=$'\n\t'

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/lib/local-certification-provenance.sh
source "$script_dir/lib/local-certification-provenance.sh"

fail() {
  printf 'Scoped provenance contract failed: %s\n' "$*" >&2
  exit 1
}

fixture_root="$(mktemp -d "${TMPDIR:-/tmp}/simplematch-provenance.XXXXXX")"
trap 'rm -rf -- "$fixture_root"' EXIT

printf '%s\n' 'tracked' >"$fixture_root/tracked.sh"
printf '%s\n' 'other' >"$fixture_root/other.txt"
git -C "$fixture_root" init -q
git -C "$fixture_root" add .

non_executable_signature="$(
  simplematch_certification_scoped_source_signature \
    "$fixture_root" test tracked.sh
)" || fail 'non-executable signature could not be calculated'
chmod 0755 "$fixture_root/tracked.sh"
executable_signature="$(
  simplematch_certification_scoped_source_signature \
    "$fixture_root" test tracked.sh
)" || fail 'executable signature could not be calculated'
[[ "$non_executable_signature" != "$executable_signature" ]] || \
  fail 'executable mode did not affect the scoped signature'

if simplematch_certification_scoped_source_signature \
    "$fixture_root" test missing.sh >/dev/null 2>&1; then
  fail 'missing declared provenance input unexpectedly passed'
fi

printf '%s\n' 'Scoped provenance contracts are valid.'
