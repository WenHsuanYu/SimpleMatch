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

runtime_scope="$(simplematch_certification_cdc_runtime_source_paths)"
verifier_scope="$(simplematch_certification_cdc_verifier_source_paths)"
grep -Fxq scripts/lib/local-certification-runtime-provenance.sh <<<"$runtime_scope" ||
  fail 'runtime provenance authority is not included in its own scope'
grep -Fxq scripts/lib/local-certification-runtime-provenance.sh <<<"$verifier_scope" ||
  fail 'runtime provenance authority is missing from the verifier scope'
for diagnostic_input in \
    scripts/lib/cdc-verifier.sh \
    scripts/lib/connect-worker-loss.sh \
    scripts/run-local-connect-worker-loss.sh; do
  if grep -Fxq "$diagnostic_input" <<<"$runtime_scope"; then
    fail "diagnostic input leaked into the CDC runtime scope: $diagnostic_input"
  fi
  grep -Fxq "$diagnostic_input" <<<"$verifier_scope" ||
    fail "diagnostic input is missing from the CDC verifier scope: $diagnostic_input"
done
for runtime_input in \
    scripts/build-local-images.sh \
    .dockerignore \
    gradlew \
    gradlew.bat \
    deploy/docker \
    deploy/compose \
    services/account-service/src/main \
    services/risk-service/src/main \
    shared-java/simplematch-contracts/src/main; do
  grep -Fxq "$runtime_input" <<<"$runtime_scope" ||
    fail "runtime input is missing from the CDC runtime scope: $runtime_input"
done

contract_script="$fixture_root/contract.sh"
printf '%s\n' '#!/usr/bin/env bash' 'printf contract' >"$contract_script"
chmod 0755 "$contract_script"
observer_script="$fixture_root/observer.sh"
printf '%s\n' '#!/usr/bin/env bash' 'printf observer' >"$observer_script"
chmod 0755 "$observer_script"
default_verifier_signature="$(
  simplematch_certification_cdc_verifier_signature "$PWD"
)" || fail 'default verifier signature could not be calculated'
override_verifier_signature="$(
  simplematch_certification_cdc_verifier_signature "$PWD" "$contract_script"
)" || fail 'override verifier signature could not be calculated'
[[ "$default_verifier_signature" != "$override_verifier_signature" ]] ||
  fail 'verifier contract override did not change its provenance identity'
observer_override_signature="$(
  simplematch_certification_cdc_verifier_signature \
    "$PWD" "$contract_script" "$observer_script"
  )" || fail 'observer override signature could not be calculated'
[[ "$override_verifier_signature" != "$observer_override_signature" ]] ||
  fail 'verifier observer override did not change its provenance identity'

printf '%s\n' 'Scoped provenance contracts are valid.'
