#!/usr/bin/env bash
set -Eeuo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
lifecycle_module="$script_dir/../lib/run-lifecycle.sh"

grep -Fq "scale_deployment redis \"\$original_redis_replicas\"" "$lifecycle_module"
grep -Fq 'SIMPLEMATCH_QUERY_SERVICE_REBUILD_HTTP_ENABLED-' "$lifecycle_module"
grep -Fq 'SIMPLEMATCH_QUERY_SERVICE_REBUILD_OPERATOR_TOKEN-' "$lifecycle_module"
grep -Fq "scale_deployment query-service \"\$original_query_replicas\"" "$lifecycle_module"
grep -Fq "restorationFailed:\$restorationFailed" "$lifecycle_module"
grep -Fq "[[ -f \"\$evidence_dir/verdict.json\" ]] && return 0" "$lifecycle_module"

printf 'Query-service certification lifecycle contract is valid.\n'
