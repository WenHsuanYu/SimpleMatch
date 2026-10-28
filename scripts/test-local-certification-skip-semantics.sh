#!/usr/bin/env bash
set -Eeuo pipefail
IFS=$'\n\t'

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd -- "$script_dir/.." && pwd)"

# A fresh run must reject occupied evidence before any external preflight or report write.
fresh_fixture="$(mktemp -d)"
trap 'rm -rf -- "$fresh_fixture"' EXIT
mkdir -p "$fresh_fixture/retained" "$fresh_fixture/bin"
printf '%s\n' 'retained original report' >"$fresh_fixture/retained/report.md"
cat >"$fresh_fixture/bin/docker" <<'EOF_DOCKER'
#!/usr/bin/env bash
printf '%s\n' invoked >"${C_EXTERNAL_MARKER:?}"
exit 23
EOF_DOCKER
chmod 755 "$fresh_fixture/bin/docker"
if C_EXTERNAL_MARKER="$fresh_fixture/external" PATH="$fresh_fixture/bin:$PATH" \
    SIMPLEMATCH_CERTIFICATION_EVIDENCE_DIR="$fresh_fixture/retained" \
    bash "$script_dir/run-local-production-like-certification.sh" \
    --skip-build --skip-compose --skip-kubernetes >"$fresh_fixture/rejected.log" 2>&1; then
  printf 'Occupied certification evidence was accepted.\n' >&2
  exit 1
fi
[[ ! -e "$fresh_fixture/external" ]] || {
  printf 'Occupied evidence reached an external command before rejection.\n' >&2
  exit 1
}
[[ "$(cat "$fresh_fixture/retained/report.md")" == 'retained original report' ]] || {
  printf 'Rejected run changed retained evidence.\n' >&2
  exit 1
}
rm -rf -- "$fresh_fixture"
trap - EXIT

# shellcheck source=scripts/lib/local-certification-phase-graph.sh
source "$script_dir/lib/local-certification-phase-graph.sh"
# shellcheck source=scripts/lib/local-certification-evidence.sh
source "$script_dir/lib/local-certification-evidence.sh"
# shellcheck source=scripts/lib/local-certification-framework.sh
source "$script_dir/lib/local-certification-framework.sh"

fail() { printf 'Certification execution contract failed: %s\n' "$*" >&2; exit 1; }
fixture_root="$(mktemp -d)"
trap 'rm -rf -- "$fixture_root"' EXIT
evidence_dir="$fixture_root/run"
mkdir "$evidence_dir"
run_id=current-run
source_signature=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
printf 'run_id=%s\nsource_signature=%s\n' "$run_id" "$source_signature" >"$evidence_dir/run-context"
git -C "$repo_root" rev-parse HEAD >"$evidence_dir/source-revision"
dry_run=false
certification_deadline_epoch=0
skip_build=true
skip_compose=true
skip_kubernetes=true
matching_fleet_only=false
image_transport=registry
image_tag='test'
image_lock="$fixture_root/local-images.lock"
compose_project='test'
compose_file="$fixture_root/compose.yml"
namespace=""
certification_trading_day=2026-10-28
failed_phase=""
failure_reason=""
completed_phases=()
command_log="$fixture_root/commands"
record_command() { printf '%s\n' called >>"$command_log"; }

# An identical command executes again. A retained PASS does not skip the call.
run_logged source-preflight record_command
run_logged source-preflight record_command
[[ "$(wc -l <"$command_log")" -eq 2 ]] || fail 'A previous PASS skipped execution.'
certification_validate_phase_result "$evidence_dir" source-preflight || fail 'Current PASS is invalid.'

# A failure replaces the old PASS and stops the rest of a shell function.
fail_early() { false; printf '%s\n' unexpected >"$fixture_root/after-failure"; }
set +e
(set -e; run_logged source-preflight fail_early) >"$fixture_root/failure.log" 2>&1
status=$?
set -e
[[ "$status" -ne 0 && ! -e "$fixture_root/after-failure" ]] || fail 'Command failure did not stop execution.'
jq -e '.status == "FAIL" and .exitCode != 0' \
  "$evidence_dir/phases/source-preflight/result.json" >/dev/null || fail 'Failure retained a PASS result.'

set +e
(set -e; run_capture compose-config "$fixture_root/captured.txt" bash -c 'printf failure-output; exit 7') \
  >"$fixture_root/capture.log" 2>&1
status=$?
set -e
[[ "$status" -eq 7 && "$(cat "$fixture_root/captured.txt")" == failure-output ]] || fail 'Capture lost output or exit status.'

# A selected partial profile has only its actual execution results.
required="$(certification_required_phase_ids)"
while IFS= read -r phase; do
  [[ -n "$phase" ]] || continue
  run_logged "$phase" true
done <<<"$required"
# The earlier failed capture is not an omitted phase PASS.
jq -e '.status == "FAIL"' "$evidence_dir/phases/compose-config/result.json" >/dev/null 2>&1 && \
  fail 'Selected compose-config did not execute again.'
[[ ! -e "$evidence_dir/phases/compose-up/result.json" ]] || fail 'An omitted phase acquired a result.'
certification_write_completion_manifest "$evidence_dir" "$required"
certification_validate_completion "$evidence_dir" "$required" || fail 'Complete partial-profile results are invalid.'
mv "$evidence_dir/phases" "$fixture_root/aliased-phases"
ln -s "$fixture_root/aliased-phases" "$evidence_dir/phases"
if certification_validate_completion "$evidence_dir" "$required" >/dev/null 2>&1; then
  fail 'A symlinked phase ancestor satisfied completion.'
fi
rm "$evidence_dir/phases"
mv "$fixture_root/aliased-phases" "$evidence_dir/phases"
write_report 0
grep -Fq -- '- status: PARTIAL' "$evidence_dir/report.md" || fail 'Partial profile became aggregate PASS.'

cp "$evidence_dir/phases/source-preflight/result.json" "$fixture_root/pass.json"
jq '.runId = "another-run"' "$fixture_root/pass.json" >"$evidence_dir/phases/source-preflight/result.json"
if certification_validate_completion "$evidence_dir" "$required" >/dev/null 2>&1; then
  fail 'A different run satisfied completion.'
fi
cp "$fixture_root/pass.json" "$evidence_dir/phases/source-preflight/result.json"
rm "$evidence_dir/phases/source-preflight/result.json"
if certification_write_completion_manifest "$evidence_dir" "$required" >/dev/null 2>&1; then
  fail 'Missing selected evidence satisfied completion.'
fi

# Retired resume fails before creating an execution directory.
if SIMPLEMATCH_CERTIFICATION_EVIDENCE_DIR="$fixture_root/resume" \
    bash "$script_dir/run-local-production-like-certification.sh" --resume \
    >"$fixture_root/resume.log" 2>&1; then
  fail 'Retired resume was accepted.'
fi
grep -Fq -- '--resume was retired' "$fixture_root/resume.log" || fail 'Resume rejection is unclear.'
[[ ! -e "$fixture_root/resume" ]] || fail 'Rejected resume created evidence.'

SIMPLEMATCH_CERTIFICATION_EVIDENCE_DIR="$fixture_root/dry-run" \
  bash "$script_dir/run-local-production-like-certification.sh" \
  --dry-run --skip-build --skip-compose --skip-kubernetes >"$fixture_root/dry-run.log"
[[ ! -e "$fixture_root/dry-run" ]] || fail 'Dry-run created evidence.'
grep -Fxq 'PHASE source-preflight' "$fixture_root/dry-run.log" || fail 'Dry-run omitted selected commands.'
grep -Eq -- '--project-name simplematch-local-production-like-[0-9]{8}-[0-9]{6}-[0-9]+' \
  "$fixture_root/dry-run.log" || fail 'Default Compose project is not run-owned.'

printf '%s\n' 'Local certification fresh execution and partial-profile contracts are valid.'
