#!/usr/bin/env bash
set -Eeuo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
runner="$script_dir/../run-certification.sh"
wrapper="$script_dir/../../../run-query-service-certification.sh"
cluster_data_module="$script_dir/../../critical-consumers/lib/cluster-data.sh"

"$runner" --help | grep -Fq -- '--retained-evidence-dir PATH'
grep -Fq 'simplematch_certification_verifier_image' "$runner"
grep -Fq 'simplematch_kind_namespace_is_disposable' "$runner"
grep -Fq 'critical-consumers/lib/matching-status.sh' "$runner"
grep -Fq 'run-risk-matching-command-e2e.sh' "$runner"
grep -Fq 'matching-fixture' "$runner"
grep -Fq -- '--side BUY' "$runner"
grep -Fq -- '--side SELL' "$runner"
grep -Fq -- '--retained-evidence-dir "$retained_evidence_dir"' "$runner"
grep -Fq "capture_consumer_state \"\$evidence_dir/critical-before.json\"" "$runner"
grep -Fq 'replay_boundary_dir="$evidence_dir/replay-boundary"' "$runner"
grep -Fq 'capture_query_replay_boundary "$replay_boundary_dir"' "$runner"
grep -Fq 'scale_deployment redis 0' "$runner"
grep -Fq 'scale_deployment query-service 0' "$runner"
grep -Fq 'critical-during-query-outage.json' "$runner"
grep -Fq 'critical-query-isolation-probe.json' "$runner"
grep -Fq 'capture_critical_path_health' "$cluster_data_module"
grep -Fq 'capture_matching_fleet_topology' "$cluster_data_module"
grep -Fq -- '--request-timeout=' "$cluster_data_module"
grep -Fq 'timeout --foreground' "$cluster_data_module"
grep -Fq 'SIMPLEMATCH_QUERY_ISOLATION_PROBE_SECONDS' "$runner"
grep -Fq 'SIMPLEMATCH_QUERY_ISOLATION_COMMAND_TIMEOUT_SECONDS' "$runner"
grep -Fq 'matching_ready_replicas' "$runner"
grep -Fq 'query-outage.json' "$runner"
grep -Fq 'quiescent critical-path isolation' "$runner"
grep -Fq 'active-liveness.sh' "$runner"
grep -Fq 'prepare_query_active_liveness' "$runner"
grep -Fq 'run_query_active_liveness' "$runner"
grep -Fq 'restore_query_active_liveness' "$runner"
if grep -Fq 'active processing liveness is reported separately' "$runner"; then
  printf '%s\n' 'Runner must execute the active liveness probe.' >&2
  exit 1
fi
grep -Fq 'scale_deployment query-service 1' "$runner"
grep -Fq 'reset_query_consumer_group query-service-matching-events matching.events' "$runner"
grep -Fq 'reset_query_consumer_group query-service-account-lifecycle account.lifecycle' "$runner"
grep -Fq 'restore_query_certification_environment' "$runner"
grep -Fq 'end-to-end/query-service/run-certification.sh' "$wrapper"

printf 'Query-service certification runner contract is valid.\n'
