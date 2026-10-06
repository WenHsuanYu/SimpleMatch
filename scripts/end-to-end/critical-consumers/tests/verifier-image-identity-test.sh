#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/lib/local-certification-provenance.sh
source "$script_dir/../../../lib/local-certification-provenance.sh"
# shellcheck source=scripts/end-to-end/critical-consumers/lib/kafka-observation-interface.sh
source "$script_dir/../lib/kafka-observation-interface.sh"
temporary_directory="$(mktemp -d /tmp/simplematch-verifier-identity.XXXXXX)"
trap 'rm -rf -- "$temporary_directory"' EXIT
config_identity=sha256:57274797ae05d9d6d3da4b5a7714c9fedb0e0e0503b3ce49791368931beaa014
index_identity=sha256:5b95b95ad9646cd8da64348486cfb364920dcc89d31618fa1d74cea9565ee61e
fixture_expected_identity="$config_identity"
execution_fails=false
simplematch_certification_image_transport() { printf '%s\n' kind-load; }
simplematch_certification_verifier_image_identity() { printf '%s\n' "$fixture_expected_identity"; }
simplematch_certification_verifier_image() { printf '%s\n' simplematch/risk-matching-e2e-verifier:local; }
kubectl() {
  printf '%s\n' '{"items":[{"metadata":{"name":"worker"},"spec":{}}]}'
}
repo_root=repository
namespace=namespace
kns() { printf '%s\n' worker; }
docker() {
  [[ "$1" == exec && "$2" == worker ]] || return 1
  case "$3" in
    crictl) command cat "$script_dir/fixtures/verifier-config-identity.json" ;;
    ctr)
      case " $* " in
        *' images ls '*) command cat "$script_dir/fixtures/verifier-index-identity.txt" ;;
        *' run '*) [[ "$execution_fails" == false ]] ;;
        *) return 1 ;;
      esac ;;
    *) return 1 ;;
  esac
}
verify() {
  simplematch_verify_kind_loaded_verifier_image_execution repository namespace evidence &&
    verify_kind_loaded_verifier_image_identity observer evidence
}

verify || { printf '%s\n' 'Legacy config identity was incorrectly rejected.' >&2; exit 1; }
printf '%s\n' 'Legacy image-config identity: PASS' >"$temporary_directory/observed.txt"
fixture_expected_identity="$index_identity"
verify || { printf '%s\n' 'OCI index identity was incorrectly rejected.' >&2; exit 1; }
printf '%s\n' 'OCI image-index identity: PASS' >>"$temporary_directory/observed.txt"
fixture_expected_identity=sha256:f112bbec5ef92a5d85af854c4f91759c459a558f2eb02915d5abc5b3224090aa
if verify; then
  printf '%s\n' 'Different retained identity was incorrectly accepted.' >&2; exit 1
fi
printf '%s\n' 'Different retained identity: REJECT' >>"$temporary_directory/observed.txt"
fixture_expected_identity="$index_identity"
execution_fails=true
if verify; then
  printf '%s\n' 'Unusable content was incorrectly accepted.' >&2; exit 1
fi
printf '%s\n' 'Matching metadata but unusable content: REJECT' >>"$temporary_directory/observed.txt"
diff -u "$script_dir/baselines/verifier-image-identity.txt" "$temporary_directory/observed.txt"
