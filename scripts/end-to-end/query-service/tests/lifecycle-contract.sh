#!/usr/bin/env bash
set -Eeuo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
lifecycle_module="$script_dir/../lib/run-lifecycle.sh"
runner="$script_dir/../run-certification.sh"
cluster_data_module="$script_dir/../../critical-consumers/lib/cluster-data.sh"
temporary_directory="$(mktemp -d)"
trap 'rm -rf -- "$temporary_directory"' EXIT

grep -Fq "scale_deployment redis \"\$original_redis_replicas\"" "$lifecycle_module"
grep -Fq 'SIMPLEMATCH_QUERY_SERVICE_REBUILD_HTTP_ENABLED-' "$lifecycle_module"
grep -Fq 'SIMPLEMATCH_QUERY_SERVICE_REBUILD_OPERATOR_TOKEN-' "$lifecycle_module"
grep -Fq "scale_deployment query-service \"\$original_query_replicas\"" "$lifecycle_module"
grep -Fq "restorationFailed:\$restorationFailed" "$lifecycle_module"
if grep -Fq "[[ -f \"\$evidence_dir/verdict.json\" ]] && return 0" "$lifecycle_module"; then
  printf '%s\n' 'Failure verdicts must invalidate an existing verdict.' >&2
  exit 1
fi

redis_flag_line="$(grep -n '^redis_scaled=true$' "$runner" | head -n 1 | cut -d: -f1)"
redis_mutation_line="$(grep -n 'scale_deployment redis 0' "$runner" | head -n 1 | cut -d: -f1)"
query_flag_line="$(grep -n '^query_scaled=true$' "$runner" | head -n 1 | cut -d: -f1)"
query_mutation_line="$(grep -n 'scale_deployment query-service 1' "$runner" | head -n 1 | cut -d: -f1)"
environment_flag_line="$(grep -n '^query_environment_modified=true$' "$runner" | head -n 1 | cut -d: -f1)"
environment_mutation_line="$(grep -n 'SIMPLEMATCH_QUERY_SERVICE_REBUILD_HTTP_ENABLED=true' "$runner" | head -n 1 | cut -d: -f1)"
(( redis_flag_line < redis_mutation_line )) ||
  { printf '%s\n' 'Redis restoration flag must be set before scale mutation.' >&2; exit 1; }
(( query_flag_line < query_mutation_line )) ||
  { printf '%s\n' 'Query restoration flag must be set before scale mutation.' >&2; exit 1; }
(( environment_flag_line < environment_mutation_line )) ||
  { printf '%s\n' 'Query environment flag must be set before env mutation.' >&2; exit 1; }

(
  source "$lifecycle_module"
  original_redis_replicas=1
  original_query_replicas=1
  redis_scaled=true
  query_scaled=true
  query_environment_modified=true
  query_port_forward_pid=""
  restoration_failed=false
  stop_background_process() { :; }
  scale_deployment() { return 1; }
  kns() { return 1; }
  restore_query_certification_environment
  [[ "$redis_scaled" == true && "$query_scaled" == true &&
     "$query_environment_modified" == true && "$restoration_failed" == true ]]
) || {
  printf '%s\n' 'Failed lifecycle mutations must remain marked for cleanup retry.' >&2
  exit 1
}

(
  source "$lifecycle_module"
  original_redis_replicas=1
  original_query_replicas=1
  redis_scaled=true
  query_scaled=true
  query_environment_modified=true
  query_port_forward_pid=""
  restoration_failed=false
  stop_background_process() { :; }
  scale_deployment() { return 0; }
  kns() { return 0; }
  restore_query_certification_environment
  [[ "$redis_scaled" == false && "$query_scaled" == false &&
     "$query_environment_modified" == false && "$restoration_failed" == false ]]
) || {
  printf '%s\n' 'Successful lifecycle restoration must clear mutation flags.' >&2
  exit 1
}

(
  source "$cluster_data_module"
  source "$lifecycle_module"
  original_redis_replicas=1
  original_query_replicas=1
  redis_scaled=true
  query_scaled=true
  query_environment_modified=true
  query_port_forward_pid=""
  restoration_failed=false
  stop_background_process() { :; }
  kns() { return 0; }
  wait_deployment_replicas() { return 1; }
  die() { printf '%s\n' 'scale_deployment must not terminate lifecycle cleanup.' >&2; return 99; }
  restore_query_certification_environment
  [[ "$redis_scaled" == true && "$query_scaled" == true &&
     "$query_environment_modified" == false && "$restoration_failed" == true ]]
) || {
  printf '%s\n' 'Deployment scale failures must return to lifecycle cleanup.' >&2
  exit 1
}

(
  source "$lifecycle_module"
  evidence_dir="$temporary_directory/overwrite"
  mkdir -p "$evidence_dir"
  evidence_initialized=true
  namespace=certification
  current_stage="restore certification environment"
  failure_reason="environment restoration failed"
  restoration_failed=true
  printf '%s\n' '{"status":"PASS"}' >"$evidence_dir/verdict.json"
  write_query_failure_verdict 1
  jq -e '
    .status == "FAIL"
    and .exitStatus == 1
    and .restorationFailed == true
    and .reason == "environment restoration failed"
  ' "$evidence_dir/verdict.json" >/dev/null
) || {
  printf '%s\n' 'Restoration failure must overwrite a stale PASS verdict.' >&2
  exit 1
}

printf 'Query-service certification lifecycle contract is valid.\n'
