#!/usr/bin/env bash
set -Eeuo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
test_root="$(mktemp -d "${TMPDIR:-/tmp}/simplematch-budget-evidence.XXXXXX")"
trap 'rm -rf -- "$test_root"' EXIT

(
  # The framework's EXIT trap belongs to the real runner, not this fixture.
  source "$script_dir/lib/local-certification-framework.sh"
  trap - EXIT
  evidence_dir="$test_root"
  certification_plan_file="$test_root/no-plan.json"
  dry_run=false
  skip_build=false
  skip_compose=false
  skip_kubernetes=false
  matching_fleet_only=false
  image_tag=test
  image_transport=kind-load
  compose_project=test
  compose_file="$test_root/compose.yml"
  repo_root="$test_root"
  namespace=""
  certification_trading_day=2026-10-06
  failed_phase=""
  failure_reason=""
  completed_phases=()

  printf '%s\n' '{"requests_within_host_budget":false}' >"$test_root/local-resource-budget.json"
  write_report 1
  if grep -Fq 'declared_resource_budget:' "$test_root/report.md"; then
    printf 'A stale budget report was linked after a failed fresh phase.\n' >&2
    exit 1
  fi

  completed_phases=(local-resource-budget)
  write_report 0
  grep -Fq 'exceeds selected host capacity' "$test_root/report.md"
  grep -Fq 'resource_budget_evidence: local-resource-budget.json' "$test_root/report.md"

  source "$script_dir/lib/local-certification-artifacts.sh"
  outputs="$(certification_phase_outputs_json local-resource-budget)"
  jq -e --arg digest "sha256:$(sha256sum "$test_root/local-resource-budget.json" | awk '{print $1}')" \
    'length == 1 and .[0].name == "local-resource-budget" and .[0].identity == $digest' \
    <<<"$outputs" >/dev/null
  printf '{"outputs":%s}\n' "$outputs" >"$test_root/phase-result.json"
  certification_phase_current_outputs_valid local-resource-budget "$test_root/phase-result.json"
  printf '%s\n' '{"requests_within_host_budget":true}' >"$test_root/local-resource-budget.json"
  if certification_phase_current_outputs_valid local-resource-budget "$test_root/phase-result.json"; then
    printf 'Changed budget evidence still passed current-output validation.\n' >&2
    exit 1
  fi
)

printf 'Local resource budget evidence report checks passed.\n'
