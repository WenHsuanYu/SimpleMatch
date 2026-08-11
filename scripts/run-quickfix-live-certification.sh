#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd -- "$script_dir/.." && pwd)"

die() {
  printf '%s\n' "$*" >&2
  exit 1
}

usage() {
  printf '%s\n' \
    'Usage: run-quickfix-live-certification.sh' \
    '' \
    'Required environment:' \
    '  SIMPLEMATCH_LIVE_FIX_HOST' \
    '  SIMPLEMATCH_LIVE_FIX_PORT' \
    '  SIMPLEMATCH_LIVE_FIX_ACCOUNT_ID' \
    '  SIMPLEMATCH_LIVE_FIX_SYMBOL' \
    '' \
    'Optional environment:' \
    '  SIMPLEMATCH_LIVE_FIX_SENDER_COMP_ID       default: CLIENT' \
    '  SIMPLEMATCH_LIVE_FIX_TARGET_COMP_ID       default: SIMPLEMATCH' \
    '  SIMPLEMATCH_LIVE_FIX_QUANTITY             default: 10' \
    '  SIMPLEMATCH_LIVE_FIX_PRICE                default: 101.25' \
    '  SIMPLEMATCH_LIVE_FIX_CL_ORD_ID            generated when absent' \
    '  SIMPLEMATCH_LIVE_FIX_DICTIONARY           default: config/quickfix/fix-spec/FIX44.xml' \
    '  SIMPLEMATCH_LIVE_FIX_EXPECT_ACCEPTED      default: true'
}

if [[ "${1:-}" == "--help" || "${1:-}" == "-h" ]]; then
  usage
  exit 0
fi
[[ $# -eq 0 ]] || die "Unknown argument: $1"

for required_name in \
  SIMPLEMATCH_LIVE_FIX_HOST \
  SIMPLEMATCH_LIVE_FIX_PORT \
  SIMPLEMATCH_LIVE_FIX_ACCOUNT_ID \
  SIMPLEMATCH_LIVE_FIX_SYMBOL; do
  [[ -n "${!required_name:-}" ]] || die "$required_name is required"
done

[[ -x "$repo_root/gradlew" ]] || die 'Gradle wrapper is missing'

cd "$repo_root"
./gradlew --no-daemon :services:quickfix-gateway:liveCertificationTest
