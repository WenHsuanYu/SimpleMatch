#!/usr/bin/env bash

# Reversible lifecycle for query certification. The caller owns run state.
stop_query_port_forward() {
  stop_background_process "${query_port_forward_pid:-}"
  query_port_forward_pid=""
  query_port=""
}

restore_query_certification_environment() {
  set +e
  stop_query_port_forward
  if [[ "$redis_scaled" == true && -n "$original_redis_replicas" ]]; then
    scale_deployment redis "$original_redis_replicas" || restoration_failed=true
    redis_scaled=false
  fi
  if [[ "$query_environment_modified" == true ]]; then
    kns set env deployment/query-service \
      SIMPLEMATCH_QUERY_SERVICE_REBUILD_HTTP_ENABLED- \
      SIMPLEMATCH_QUERY_SERVICE_REBUILD_OPERATOR_TOKEN- >/dev/null 2>&1 ||
      restoration_failed=true
    query_environment_modified=false
  fi
  if [[ "$query_scaled" == true && -n "$original_query_replicas" ]]; then
    scale_deployment query-service "$original_query_replicas" || restoration_failed=true
    query_scaled=false
  elif [[ -n "$original_query_replicas" ]]; then
    kns rollout status deployment/query-service \
      --timeout="${timeout_seconds}s" >/dev/null 2>&1 || restoration_failed=true
  fi
  set -e
}

write_query_failure_verdict() {
  local status="$1"
  [[ "$evidence_initialized" == true ]] || return 0
  [[ -f "$evidence_dir/verdict.json" ]] && return 0
  jq -n \
    --arg namespace "$namespace" \
    --arg stage "$current_stage" \
    --arg reason "${failure_reason:-unexpected command failure}" \
    --argjson exitStatus "$status" \
    --argjson restorationFailed "$([[ "$restoration_failed" == true ]] && echo true || echo false)" \
    '{status:"FAIL",namespace:$namespace,stage:$stage,reason:$reason,
      exitStatus:$exitStatus,restorationFailed:$restorationFailed}' \
    >"$evidence_dir/verdict.json"
}

cleanup_query_certification() {
  local status="$?"
  trap - ERR EXIT INT TERM
  restore_query_certification_environment
  if [[ "$restoration_failed" == true ]]; then
    status=1
    [[ -n "$failure_reason" ]] || failure_reason="environment restoration failed"
  fi
  if (( status != 0 )) || [[ "$certification_succeeded" != true ]]; then
    write_query_failure_verdict "$status"
  fi
  exit "$status"
}
