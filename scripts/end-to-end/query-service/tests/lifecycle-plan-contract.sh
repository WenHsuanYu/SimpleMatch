#!/usr/bin/env bash
set -Eeuo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
source "$root/scripts/lib/local-certification-phase-graph.sh"
query_security_lifecycle_only=true
actual="$(certification_required_phase_ids)"
expected=$'source-preflight\nlocal-image-inventory\nlocal-image-build/query-service\nquery-security-lifecycle'
[[ "$actual" == "$expected" ]] || {
  printf '%s\n' 'Query lifecycle must select only its four declared phases.' >&2
  exit 1
}
[[ "$(certification_phase_policy query-security-lifecycle)" == FRESH ]]
[[ "$(certification_phase_definition_version query-security-lifecycle)" == 1 ]]
bash "$root/scripts/run-query-security-lifecycle.sh" --help | grep -Fq -- '--directory'
if bash "$root/scripts/run-query-security-lifecycle.sh" --resume 2>/dev/null; then
  printf '%s\n' 'A lifecycle proof cannot resume by initializing another V1.' >&2
  exit 1
fi
unset query_security_lifecycle_only
if certification_required_phase_ids | grep -Fxq query-security-lifecycle; then
  printf '%s\n' 'Focused Query lifecycle must not expand the default certification.' >&2
  exit 1
fi
printf '%s\n' 'Focused Query lifecycle phase graph is valid.'
