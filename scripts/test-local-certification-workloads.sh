#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd -- "$script_dir/.." && pwd)"
# shellcheck source=scripts/lib/local-certification-workloads.sh
source "$script_dir/lib/local-certification-workloads.sh"

namespace=cached-matching-test
image_transport=kind-load
_certification_matching_reference_argument() { printf '%s\n' 'simplematch-matching:local'; }
invoked=false
bash() {
  [[ "$1" == "$repo_root/scripts/verify-matching-fleet-live.sh" ]] || return 1
  shift
  invoked=true
  observed_namespace=''
  observed_image=''
  observed_image_selected=false
  observed_shared=false
  while (( $# > 0 )); do
    case "$1" in
      --namespace)
        (( $# >= 2 )) || return 1
        observed_namespace="$2"
        shift 2 ;;
      --allow-local-image)
        (( $# >= 2 )) || return 1
        observed_image_selected=true
        observed_image="$2"
        shift 2 ;;
      --allow-shared-node)
        observed_shared=true
        shift ;;
      *) return 1 ;;
    esac
  done
}

verify_local_matching_fleet
[[ "$invoked" == true && "$observed_namespace" == "$namespace" &&
   "$observed_shared" == true && "$observed_image_selected" == true &&
   "$observed_image" == 'simplematch-matching:local' ]]

image_transport=registry
invoked=false
verify_local_matching_fleet
[[ "$invoked" == true && "$observed_namespace" == "$namespace" &&
   "$observed_shared" == true && "$observed_image_selected" == false ]]

image_transport=kind-load
_certification_matching_reference_argument() { return 1; }
invoked=false
if verify_local_matching_fleet; then
  printf '%s\n' 'fleet verification must reject an unresolved image' >&2
  exit 1
fi
[[ "$invoked" == false ]]
printf '%s\n' 'Fleet verifier receives the selected namespace and image; missing images fail closed.'
