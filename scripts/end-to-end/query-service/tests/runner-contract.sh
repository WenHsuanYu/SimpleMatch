#!/usr/bin/env bash
set -Eeuo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
runner="$script_dir/../run-certification.sh"
wrapper="$script_dir/../../../run-query-service-certification.sh"

"$runner" --help | grep -Fq -- '--retained-evidence-dir PATH'
grep -Fq 'simplematch_certification_verifier_image' "$runner"
grep -Fq 'simplematch_kind_namespace_is_disposable' "$runner"
grep -Fq "capture_consumer_state \"\$evidence_dir/critical-before.json\"" "$runner"
grep -Fq 'scale_deployment redis 0' "$runner"
grep -Fq 'scale_deployment query-service 1' "$runner"
grep -Fq 'reset_query_consumer_group query-service-matching-events matching.events' "$runner"
grep -Fq 'reset_query_consumer_group query-service-account-lifecycle account.lifecycle' "$runner"
grep -Fq 'restore_query_certification_environment' "$runner"
grep -Fq 'end-to-end/query-service/run-certification.sh' "$wrapper"

printf 'Query-service certification runner contract is valid.\n'
