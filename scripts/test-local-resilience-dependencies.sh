#!/usr/bin/env bash
set -Eeuo pipefail
IFS=$'\n\t'

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/lib/local-resilience-dependencies.sh
source "$script_dir/lib/local-resilience-dependencies.sh"

fail() {
  printf 'Local dependency resilience contract failed: %s\n' "$*" >&2
  exit 1
}

fixture_dir="$(mktemp -d "${TMPDIR:-/tmp}/simplematch-dependency-resilience.XXXXXX")"
trap 'rm -rf -- "$fixture_dir"' EXIT

worker_stop='{"node":"simplematch-live-worker","container_id":"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef","container_id_after":"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef","node_not_ready_observed":true,"same_container_restarted":true}'

postgres_report="$fixture_dir/postgresql.json"
jq -n --argjson worker_stop "$worker_stop" '
  {
    schema_version: 1,
    profile: "dependency-recovery",
    component: "postgresql",
    status: "PASSED",
    cluster: "simplematch-live",
    context: "kind-simplematch-live",
    namespace: "simplematch-resilience-run-1",
    run_id: "run-1",
    fault_mode: "worker-stop",
    deadline_seconds: 300,
    target: {
      before: {pod:"postgres-0",pod_uid:"postgres-before",node:"simplematch-live-worker",worker_slot:"0",pvc:"postgres-data-postgres-0",pv:"postgres-pv"},
      after: {pod:"postgres-0",pod_uid:"postgres-after",node:"simplematch-live-worker",worker_slot:"0",pvc:"postgres-data-postgres-0",pv:"postgres-pv"}
    },
    worker_stop: $worker_stop,
    recovery: {ready:true, durable_marker:"marker-1", durable_before:true, durable_after:true, data_preserved:true},
    failure_reason: null,
    claim_boundary: ["local PostgreSQL same-worker PVC and durable-row recovery"]
  }
' >"$postgres_report"
resilience_dependency_report_is_valid postgresql "$postgres_report" || fail 'valid PostgreSQL report was rejected'
resilience_dependency_report_is_passed postgresql "$postgres_report" || fail 'valid PostgreSQL report did not pass'
resilience_dependency_valid_component postgresql || fail 'PostgreSQL component was rejected'
if resilience_dependency_valid_component unknown; then fail 'unknown component was accepted'; fi
[[ "$(resilience_dependency_report_status "$postgres_report")" == PASSED ]] || fail 'report status was not exposed'
[[ -z "$(resilience_dependency_report_failure_reason "$postgres_report")" ]] || fail 'successful report exposed a failure reason'
jq '.worker_stop.container_id = "short-id"' "$postgres_report" >"$fixture_dir/postgresql-short-worker-id.json"
if resilience_dependency_report_is_passed postgresql "$fixture_dir/postgresql-short-worker-id.json"; then
  fail 'short worker container identity unexpectedly passed'
fi
jq '.worker_stop.node = "simplematch-live-worker2"' "$postgres_report" >"$fixture_dir/postgresql-unrelated-worker.json"
if resilience_dependency_report_is_passed postgresql "$fixture_dir/postgresql-unrelated-worker.json"; then
  fail 'unrelated worker evidence unexpectedly passed'
fi
jq '.fault_mode = "pod-restart" | .worker_stop = null | .target.after.pod_uid = .target.before.pod_uid' "$postgres_report" >"$fixture_dir/postgresql-unchanged-pod-restart.json"
if resilience_dependency_report_is_passed postgresql "$fixture_dir/postgresql-unchanged-pod-restart.json"; then
  fail 'unchanged Pod restart evidence unexpectedly passed'
fi
jq '.failure_reason = "misleading pass reason"' "$postgres_report" >"$fixture_dir/postgresql-pass-reason.json"
if resilience_dependency_report_is_passed postgresql "$fixture_dir/postgresql-pass-reason.json"; then
  fail 'successful report with a failure reason unexpectedly passed'
fi

jq '.target.after.node = "simplematch-live-worker2"' "$postgres_report" >"$fixture_dir/postgresql-cross-node.json"
if resilience_dependency_report_is_passed postgresql "$fixture_dir/postgresql-cross-node.json"; then
  fail 'PostgreSQL cross-node recovery unexpectedly passed'
fi
jq '.recovery.durable_after = false | .recovery.data_preserved = false' "$postgres_report" >"$fixture_dir/postgresql-empty.json"
if resilience_dependency_report_is_passed postgresql "$fixture_dir/postgresql-empty.json"; then
  fail 'PostgreSQL empty replacement unexpectedly passed'
fi

redis_report="$fixture_dir/redis.json"
jq -n --argjson worker_stop "$worker_stop" '
  {
    schema_version: 1,
    profile: "dependency-recovery",
    component: "redis",
    status: "PASSED",
    cluster: "simplematch-live",
    context: "kind-simplematch-live",
    namespace: "simplematch-resilience-run-1",
    run_id: "run-1",
    fault_mode: "worker-stop",
    deadline_seconds: 300,
    target: {
      before: {pod:"redis-abc",pod_uid:"redis-before",node:"simplematch-live-worker2",worker_slot:"2",pvc:null},
      after: {pod:"redis-def",pod_uid:"redis-after",node:"simplematch-live-worker3",worker_slot:"1",pvc:null}
    },
    worker_stop: ($worker_stop | .node = "simplematch-live-worker2"),
    recovery: {ready:true, portable:true, rescheduled_after_worker_loss:true, disposable_state:true, marker_before:true, marker_after:false, marker_required_after:false},
    failure_reason: null,
    claim_boundary: ["local Redis readiness after portable worker recovery", "Redis state is disposable"]
  }
' >"$redis_report"
resilience_dependency_report_is_valid redis "$redis_report" || fail 'valid Redis report was rejected'
resilience_dependency_report_is_passed redis "$redis_report" || fail 'valid Redis report did not pass'
jq '.recovery.rescheduled_after_worker_loss = false' "$redis_report" >"$fixture_dir/redis-no-reschedule.json"
if resilience_dependency_report_is_passed redis "$fixture_dir/redis-no-reschedule.json"; then
  fail 'Redis worker-loss evidence without rescheduling unexpectedly passed'
fi
jq '.target.after.pvc = "unexpected-pvc"' "$redis_report" >"$fixture_dir/redis-pvc.json"
if resilience_dependency_report_is_valid redis "$fixture_dir/redis-pvc.json"; then
  fail 'Redis PVC report unexpectedly passed validation'
fi

kafka_report="$fixture_dir/kafka.json"
jq -n --argjson worker_stop "$worker_stop" '
  {
    schema_version: 1,
    profile: "dependency-recovery",
    component: "kafka",
    status: "PASSED",
    cluster: "simplematch-live",
    context: "kind-simplematch-live",
    namespace: "simplematch-resilience-run-1",
    run_id: "run-1",
    fault_mode: "worker-stop",
    deadline_seconds: 300,
    target: {
      ordinal: 1,
      before: {pod:"kafka-1",pod_uid:"kafka-before",node:"simplematch-live-worker",worker_slot:"0",pvc:"kafka-data-kafka-1",pv:"kafka-pv-1",cluster_id:"5L6g3nShT-eMCtK--X86sw",node_id:1},
      after: {pod:"kafka-1",pod_uid:"kafka-after",node:"simplematch-live-worker",worker_slot:"0",pvc:"kafka-data-kafka-1",pv:"kafka-pv-1",cluster_id:"5L6g3nShT-eMCtK--X86sw",node_id:1}
    },
    brokers_before: [
      {pod:"kafka-0",pod_uid:"k0-before",node:"simplematch-live-worker2",worker_slot:"1",pvc:"kafka-data-kafka-0",pv:"kafka-pv-0",cluster_id:"5L6g3nShT-eMCtK--X86sw",node_id:0},
      {pod:"kafka-1",pod_uid:"k1-before",node:"simplematch-live-worker",worker_slot:"0",pvc:"kafka-data-kafka-1",pv:"kafka-pv-1",cluster_id:"5L6g3nShT-eMCtK--X86sw",node_id:1},
      {pod:"kafka-2",pod_uid:"k2-before",node:"simplematch-live-worker3",worker_slot:"2",pvc:"kafka-data-kafka-2",pv:"kafka-pv-2",cluster_id:"5L6g3nShT-eMCtK--X86sw",node_id:2}
    ],
    brokers_after: [
      {pod:"kafka-0",pod_uid:"k0-after",node:"simplematch-live-worker2",worker_slot:"1",pvc:"kafka-data-kafka-0",pv:"kafka-pv-0",cluster_id:"5L6g3nShT-eMCtK--X86sw",node_id:0},
      {pod:"kafka-1",pod_uid:"k1-after",node:"simplematch-live-worker",worker_slot:"0",pvc:"kafka-data-kafka-1",pv:"kafka-pv-1",cluster_id:"5L6g3nShT-eMCtK--X86sw",node_id:1},
      {pod:"kafka-2",pod_uid:"k2-after",node:"simplematch-live-worker3",worker_slot:"2",pvc:"kafka-data-kafka-2",pv:"kafka-pv-2",cluster_id:"5L6g3nShT-eMCtK--X86sw",node_id:2}
    ],
    worker_stop: $worker_stop,
    quorum: {ready_before:true, available_during:2, isr_before:3, isr_after:3, restored:true},
    marker: {topic:"simplematch-resilience-run-1",key:"marker-1",committed_before:true,preserved_after:true,record_count_before:1,record_count_after:1},
    topic_contract: {verified:true, topics:["matching.commands", "matching.events", "account.lifecycle", "marketdata.events", "simplematch-connect-configs", "simplematch-connect-offsets", "simplematch-connect-status"], producer_acks:"all", producer_idempotence:true},
    recovery: {ready:true, rejoined:true, formatted_again:false, catch_up_complete:true, catch_up_probe:"log-dirs-offset-lag-zero"},
    failure_reason: null,
    claim_boundary: ["local Kafka RF3 committed-marker recovery after one worker stop"]
  }
' >"$kafka_report"
resilience_dependency_report_is_valid kafka "$kafka_report" || fail 'valid Kafka report was rejected'
resilience_dependency_report_is_passed kafka "$kafka_report" || fail 'valid Kafka report did not pass'
jq '.target.after.cluster_id = "wrong-cluster"' "$kafka_report" >"$fixture_dir/kafka-cluster-mismatch.json"
if resilience_dependency_report_is_passed kafka "$fixture_dir/kafka-cluster-mismatch.json"; then
  fail 'Kafka cluster identity mismatch unexpectedly passed'
fi
jq 'del(.worker_stop)' "$kafka_report" >"$fixture_dir/kafka-missing-worker-evidence.json"
if resilience_dependency_report_is_passed kafka "$fixture_dir/kafka-missing-worker-evidence.json"; then
  fail 'Kafka missing worker evidence unexpectedly passed'
fi
jq '.recovery.catch_up_probe = "ready-only"' "$kafka_report" >"$fixture_dir/kafka-unobserved-catch-up.json"
if resilience_dependency_report_is_passed kafka "$fixture_dir/kafka-unobserved-catch-up.json"; then
  fail 'Kafka unobserved catch-up unexpectedly passed'
fi
jq '.brokers_after[2].worker_slot = "0"' "$kafka_report" >"$fixture_dir/kafka-duplicate-worker-slot.json"
if resilience_dependency_report_is_passed kafka "$fixture_dir/kafka-duplicate-worker-slot.json"; then
  fail 'Kafka duplicate worker slot unexpectedly passed'
fi

unsupported="$fixture_dir/unsupported.json"
jq -n '{schema_version:1,profile:"dependency-recovery",component:"kafka",status:"UNSUPPORTED",cluster:"simplematch-live",context:"kind-simplematch-live",namespace:"simplematch-resilience-run-1",run_id:"run-1",fault_mode:"worker-stop",deadline_seconds:300,target:{},failure_reason:"cluster unavailable",claim_boundary:[]}' >"$unsupported"
resilience_dependency_report_is_valid kafka "$unsupported" || fail 'unsupported report was rejected'
if resilience_dependency_report_is_passed kafka "$unsupported"; then
  fail 'unsupported report unexpectedly passed'
fi

runtime_script="$script_dir/run-local-resilience-dependencies.sh"
bash -n "$runtime_script"
dry_run="$("$runtime_script" --component postgresql --namespace simplematch-resilience-test --dry-run)"
grep -Fq 'capture exact identity' <<<"$dry_run" || fail 'runtime diagnostic dry-run is incomplete'
grep -Fq 'simplematch.io/lifecycle' "$runtime_script" || fail 'runtime diagnostic lacks namespace ownership guard'
grep -Fq 'PVC/PV' "$runtime_script" || fail 'runtime diagnostic lacks storage continuity guard'
grep -Fq 'container identity' "$runtime_script" || fail 'runtime diagnostic lacks worker identity guard'
grep -Fq 'wait_for_kafka_set_ready ""' "$runtime_script" || fail 'runtime diagnostic lacks a stable Kafka baseline wait'
if grep -Fq 'CREATE TABLE' "$runtime_script"; then
  fail 'dependency diagnostic must not perform runtime PostgreSQL DDL'
fi
if grep -Fq 'risk_service.cdc_delivery_lag' "$runtime_script"; then
  fail 'dependency diagnostic must not write observer-owned CDC lag data'
fi
grep -Fq 'risk_service.local_resilience_marker' "$runtime_script" ||
  fail 'PostgreSQL marker must use the dedicated Flyway-owned table'
grep -Fq 'timeout --foreground' "$runtime_script" ||
  fail 'dependency diagnostic must bound external commands'
grep -Fq 'run_bounded' "$runtime_script" ||
  fail 'dependency diagnostic lacks its bounded command seam'
grep -Fq 'jq -er' "$runtime_script" ||
  fail 'dependency diagnostic must fail closed on malformed node readiness evidence'
grep -Fq 'Ready condition is missing' "$runtime_script" ||
  fail 'node readiness guard does not reject missing readiness conditions'
grep -Fq 'Ready condition is neither True nor False' "$runtime_script" ||
  fail 'node readiness guard does not reject ambiguous readiness conditions'
grep -Fq '== false ]]' "$runtime_script" ||
  fail 'node readiness guard does not require explicit Ready=false evidence'
grep -Fq 'kafka_marker_topic_absent' "$runtime_script" ||
  fail 'dependency diagnostic must verify Kafka marker deletion'
grep -Fq 'could not read Redis marker after recovery' "$runtime_script" ||
  fail 'Redis marker read failures must fail closed'
grep -Fq 'Redis marker returned an unexpected value' "$runtime_script" ||
  fail 'Redis marker output must be validated after recovery'
grep -Fq 'wait_for_redis_reschedule' "$runtime_script" ||
  fail 'Redis worker-loss diagnostic must observe portable rescheduling'
grep -Fq 'rescheduled_after_worker_loss' "$runtime_script" ||
  fail 'Redis report must record worker-loss rescheduling evidence'
grep -Fq 'could not list Kafka topics before marker creation' "$runtime_script" ||
  fail 'Kafka marker creation must prove topic-list availability'
grep -Fq 'kafka-log-dirs.sh' "$runtime_script" || fail 'Kafka diagnostic lacks a follower catch-up probe'
grep -Fq -- '--producer-property acks=all' "$runtime_script" || fail 'Kafka marker producer lacks a durable acknowledgement contract'
grep -Fq 'matching.commands' "$runtime_script" || fail 'Kafka diagnostic lacks topic contract verification'
if grep -Fq 'kubectl delete namespace' "$runtime_script"; then
  fail 'dependency diagnostic must not delete the caller namespace'
fi

printf '%s\n' 'Local dependency resilience report contracts passed.'
