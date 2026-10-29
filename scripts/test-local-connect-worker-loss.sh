#!/usr/bin/env bash
set -Eeuo pipefail
IFS=$'\n\t'

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd -- "$script_dir/.." && pwd)"
# shellcheck source=scripts/testdata/focused-diagnostic/fixture.sh
source "$script_dir/testdata/focused-diagnostic/fixture.sh"

fixture_root="$(mktemp -d "${TMPDIR:-/tmp}/simplematch-connect-cli.XXXXXX")"
trap 'rm -rf -- "$fixture_root"' EXIT
report_case_failure() {
  printf 'Connect CLI contract failed: %s\n' "${mode:-fixture setup}" >&2
  if [[ -f "${case_dir:-}/output" ]]; then cat "$case_dir/output" >&2; fi
}
trap report_case_failure ERR
export SIMPLEMATCH_LOCAL_REGISTRY_PORT=5001
source_signature="$(simplematch_certification_source_signature "$repo_root")"
current_runtime_signature="$(simplematch_certification_cdc_runtime_signature "$repo_root")"
current_verifier_signature="$(simplematch_certification_cdc_verifier_signature "$repo_root")"

for mode in success uid-drift delete-failed delete-output-mismatch cleanup-failed; do
  case_dir="$fixture_root/$mode"
  mkdir -p "$case_dir/bin"
  write_fixture "$case_dir/retained" "$source_signature"
  printf 'sha256:%064d\n' 0 >"$case_dir/retained/verifier-image-identity"
  write_fake_kubectl "$case_dir/base-kubectl"
  write_fake_verifier_contract "$case_dir/contract"
  for tool in kubectl kind docker curl; do
    ln -s "$script_dir/testdata/recovery-runners/command.rb" "$case_dir/bin/$tool"
  done
  status=0
  (
    cd -- "$case_dir"
    export PATH="$case_dir/bin:$PATH" RECOVERY_TEST_ROOT="$case_dir" RECOVERY_TEST_MODE="$mode"
    export RECOVERY_TEST_DATA="$repo_root/tools/recovery-evidence-verifier/src/test/resources/connect"
    export FAKE_KUBECTL_ROOT="$case_dir/retained"
    export FAKE_VERIFIER_CONTRACT_MARKER="$case_dir/contract-ran"
    export SIMPLEMATCH_CDC_OBSERVER_CONTRACT_SCRIPT="$case_dir/contract"
    "$script_dir/run-local-connect-worker-loss.sh" \
      --namespace simplematch-local-cert-focused --namespace-run-id 20260903-123456-1 \
      --retained-evidence-dir retained --evidence-dir result --deadline-seconds 60
  ) >"$case_dir/output" 2>&1 || status=$?
  if [[ "$mode" == success ]]; then
    [[ "$status" == 0 ]] || { cat "$case_dir/output" >&2; exit 1; }
    jq -e '.status == "PASSED" and .schema_version == 3' \
      "$case_dir/result/connect-worker-loss.json" >/dev/null
    jq -e 'length == 0' "$case_dir/rows.json" >/dev/null
  else
    [[ "$status" != 0 ]] || { printf 'Unexpected success: %s\n' "$mode" >&2; exit 1; }
    jq -e '.status == "FAILED"' "$case_dir/result/connect-worker-loss.json" >/dev/null
    case "$mode" in
      uid-drift)
        ! grep -Eq 'kubectl:.*(patch pod|delete pods|INSERT INTO)' "$case_dir/calls"
        grep -Fq 'expected UID' "$case_dir/output"
        ;;
      delete-failed|delete-output-mismatch)
        ! grep -Fq 'INSERT INTO' "$case_dir/calls"
        ;;
      cleanup-failed)
        grep -Fq 'could not clean up' "$case_dir/output"
        jq -e 'length == 2' "$case_dir/rows.json" >/dev/null
        ;;
    esac
  fi
  [[ -f "$case_dir/contract-ran" && -f "$case_dir/tunnel-closed" ]] || {
    cat "$case_dir/output" >&2; exit 1;
  }
  printf 'Connect CLI: %s passed.\n' "$mode"
done
