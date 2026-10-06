#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd -- "$script_dir/.." && pwd)"
# shellcheck source=scripts/lib/local-certification-workloads.sh
source "$script_dir/lib/local-certification-workloads.sh"

temporary_directory="$(mktemp -d /tmp/simplematch-fleet-command.XXXXXX)"
trap 'rm -rf -- "$temporary_directory"' EXIT
namespace=cached-matching-test
image_transport=kind-load
# A reused build has no fresh-build shell assignment; the image adapter owns the fact.
export matching_image_reference=""
_certification_matching_reference_argument() { printf '%s\n' 'simplematch-matching:local'; }
bash() {
  [[ "$1" == "$repo_root/scripts/verify-matching-fleet-live.sh" ]]
  shift
  printf '%s\n' "$@" >"$temporary_directory/observed.txt"
}

verify_local_matching_fleet
diff -u "$script_dir/testdata/local-certification/fleet-kind-load-command.txt" \
  "$temporary_directory/observed.txt"

image_transport=registry
verify_local_matching_fleet
diff -u "$script_dir/testdata/local-certification/fleet-registry-command.txt" \
  "$temporary_directory/observed.txt"

image_transport=kind-load
_certification_matching_reference_argument() { return 1; }
rm "$temporary_directory/observed.txt"
if verify_local_matching_fleet; then
  printf '%s\n' 'fleet verification must reject an unresolved image' >&2
  exit 1
fi
[[ ! -e "$temporary_directory/observed.txt" ]]
printf '%s\n' 'Fleet verification uses the resolved image after cache reuse and fails closed without it.'
