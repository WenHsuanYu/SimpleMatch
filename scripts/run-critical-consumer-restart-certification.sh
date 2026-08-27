#!/usr/bin/env bash
set -Eeuo pipefail
IFS=$'\n\t'

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd -- "$script_dir/.." && pwd)"
# shellcheck source=scripts/lib/local-common.sh
source "$script_dir/lib/local-common.sh"
# shellcheck source=scripts/lib/local-kind.sh
source "$script_dir/lib/local-kind.sh"

cluster_name="${SIMPLEMATCH_KIND_CLUSTER_NAME:-simplematch-live}"
context="kind-$cluster_name"
namespace=""
evidence_dir=""
timeout_seconds="${SIMPLEMATCH_CRITICAL_CONSUMER_RESTART_TIMEOUT_SECONDS:-180}"

usage() {
  cat <<'EOF'
Usage:
  scripts/run-critical-consumer-restart-certification.sh \
    --namespace NAME \
    --evidence-dir PATH \
    [--timeout-seconds N]

Verifies durable restart behavior for the three matching.events consumers in an
existing local production-like namespace. The namespace must be lifecycle-labeled
disposable and must already contain processed matching.events traffic.

The scenario replaces Account and Persistence Deployment Pods, the QuickFIX
Gateway StatefulSet Pod, the PostgreSQL Pod, and one Kafka broker Pod. It proves
that durable consumer positions survive each restart while no new traffic is
admitted. It does not replace the separate dependency-outage and retained FIX
session certification.
EOF
}

die() {
  printf 'critical consumer restart certification: %s\n' "$*" >&2
  exit 1
}

while (($# > 0)); do
  case "$1" in
    --namespace)
      namespace="${2:?--namespace requires a value}"
      shift 2
      ;;
    --evidence-dir)
      evidence_dir="${2:?--evidence-dir requires a value}"
      shift 2
      ;;
    --timeout-seconds)
      timeout_seconds="${2:?--timeout-seconds requires a value}"
      shift 2
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    *)
      usage >&2
      die "unknown option: $1"
      ;;
  esac
done

[[ -n "$namespace" ]] || die '--namespace is required'
[[ -n "$evidence_dir" ]] || die '--evidence-dir is required'
[[ "$timeout_seconds" =~ ^[1-9][0-9]*$ ]] ||
  die '--timeout-seconds must be a positive integer'
(( timeout_seconds <= 600 )) || die '--timeout-seconds must not exceed 600'

for tool in kubectl jq diff seq sleep date; do
  command -v "$tool" >/dev/null 2>&1 || die "$tool is required"
done

[[ "$(kubectl config current-context)" == "$context" ]] ||
  die "current Kubernetes context must be $context"
kubectl get namespace "$namespace" >/dev/null 2>&1 ||
  die "namespace does not exist: $namespace"
simplematch_kind_namespace_is_disposable "$context" "$namespace" ||
  die 'refusing restart certification outside a lifecycle-labeled disposable namespace'

mkdir -p "$evidence_dir"
evidence_dir="$(cd -- "$evidence_dir" && pwd)"
shopt -s nullglob dotglob
existing_evidence=("$evidence_dir"/*)
shopt -u nullglob dotglob
((${#existing_evidence[@]} == 0)) ||
  die "evidence directory must be empty: $evidence_dir"
mkdir -p \
  "$evidence_dir/before" \
  "$evidence_dir/after-consumers" \
  "$evidence_dir/after-postgres" \
  "$evidence_dir/after-kafka"

kns() {
  kubectl --context "$context" -n "$namespace" "$@"
}

wait_workloads() {
  kns rollout status deployment/account-service \
    --timeout="${timeout_seconds}s" >/dev/null
  kns rollout status deployment/persistence \
    --timeout="${timeout_seconds}s" >/dev/null
  kns rollout status statefulset/quickfix-gateway \
    --timeout="${timeout_seconds}s" >/dev/null
  kns rollout status statefulset/postgres \
    --timeout="${timeout_seconds}s" >/dev/null
  kns wait --for=jsonpath='{.status.readyReplicas}'=3 statefulset/kafka \
    --timeout="${timeout_seconds}s" >/dev/null
}

postgres_pod() {
  kns get pods -l app.kubernetes.io/name=postgres -o json |
    jq -r '.items | if length == 1 then .[0].metadata.name else empty end'
}

kafka_pod() {
  kns get pods \
    -l 'app.kubernetes.io/name=kafka,app.kubernetes.io/component=broker' \
    -o json |
    jq -r '[.items[].metadata.name] | sort | .[0] // empty'
}

capture_events_offsets() {
  local destination="$1"
  local broker
  broker="$(kafka_pod)"
  [[ -n "$broker" ]] || die 'cannot resolve a Kafka broker Pod'
  kns exec "$broker" -- /opt/kafka/bin/kafka-get-offsets.sh \
    --bootstrap-server kafka:9092 \
    --topic matching.events |
    jq -Rn '
      [inputs | select(length > 0) | split(":")
        | {partition:(.[1] | tonumber), offset:(.[2] | tonumber)}]
      | sort_by(.partition)
    ' >"$destination"
  [[ "$(jq 'length' "$destination")" == 15 ]] ||
    die 'matching.events offset snapshot does not contain 15 partitions'
}

capture_database_state() {
  local destination="$1"
  local postgres
  postgres="$(postgres_pod)"
  [[ -n "$postgres" ]] || die 'cannot resolve PostgreSQL Pod'
  kns exec "$postgres" -- psql -U simplematch -d simplematch -At \
    -v ON_ERROR_STOP=1 -c "
      SELECT json_build_object(
        'persistenceProgress', COALESCE((
          SELECT json_agg(row_to_json(p) ORDER BY p.partition_id)
          FROM (
            SELECT partition_id, last_processed_offset
            FROM persistence.matching_consumer_progress
            WHERE consumer_name = 'persistence-matching-events'
          ) p
        ), '[]'::json),
        'accountProgress', COALESCE((
          SELECT json_agg(row_to_json(a) ORDER BY a.partition_id)
          FROM (
            SELECT partition_id, last_processed_offset
            FROM account_service.matching_event_consumer_progress
            WHERE consumer_name = 'account-final-matching-events'
          ) a
        ), '[]'::json),
        'quickfixProgress', COALESCE((
          SELECT json_agg(row_to_json(q) ORDER BY q.partition_id)
          FROM (
            SELECT partition_id, last_processed_offset
            FROM quickfix_gateway.matching_consumer_progress
            WHERE consumer_name = 'quickfix-final-matching-events'
          ) q
        ), '[]'::json),
        'persistenceQuarantines', (
          SELECT COUNT(*) FROM persistence.matching_consumer_quarantines
          WHERE status = 'QUARANTINED'
        ),
        'accountQuarantines', (
          SELECT COUNT(*) FROM account_service.matching_event_consumer_quarantines
          WHERE status = 'QUARANTINED'
        ),
        'quickfixQuarantines', (
          SELECT COUNT(*) FROM quickfix_gateway.matching_consumer_quarantines
          WHERE status = 'QUARANTINED'
        ),
        'quickfixPendingIntents', (
          SELECT COUNT(*) FROM quickfix_gateway.fix_delivery_intents
          WHERE status = 'PENDING'
        )
      )::text;
    " | jq . >"$destination"
}

capture_pod_uids() {
  local selector="$1"
  local destination="$2"
  kns get pods -l "$selector" -o json |
    jq '[.items[].metadata.uid] | sort' >"$destination"
  (( $(jq 'length' "$destination") > 0 )) ||
    die "selector has no Pods: $selector"
}

wait_all_pods_replaced() {
  local selector="$1"
  local before="$2"
  local after="$3"
  local expected
  expected="$(jq 'length' "$before")"
  for _ in $(seq 1 "$timeout_seconds"); do
    capture_pod_uids "$selector" "$after"
    if jq -e -n \
        --argjson expected "$expected" \
        --slurpfile before "$before" \
        --slurpfile after "$after" '
          ($after[0] | length) == $expected
          and ($before[0] | all(. as $uid | ($after[0] | index($uid) | not)))
        ' >/dev/null; then
      return 0
    fi
    sleep 1
  done
  die "Pods were not fully replaced for selector: $selector"
}

wait_named_pod_replaced() {
  local name="$1"
  local old_uid="$2"
  local kind="$3"
  for _ in $(seq 1 "$timeout_seconds"); do
    local uid
    local ready
    uid="$(kns get pod "$name" -o jsonpath='{.metadata.uid}' 2>/dev/null || true)"
    ready="$(
      kns get pod "$name" -o json 2>/dev/null |
        jq -r 'any(.status.conditions[]?;
          .type == "Ready" and .status == "True")' 2>/dev/null || true
    )"
    if [[ -n "$uid" && "$uid" != "$old_uid" && "$ready" == true ]]; then
      return 0
    fi
    sleep 1
  done
  die "$kind Pod $name was not replaced and Ready within ${timeout_seconds}s"
}

require_same_json() {
  local before="$1"
  local after="$2"
  local description="$3"
  if ! diff -u <(jq -S . "$before") <(jq -S . "$after") >/dev/null; then
    diff -u <(jq -S . "$before") <(jq -S . "$after") >&2 || true
    die "$description"
  fi
}

require_healthy_state() {
  local state="$1"
  jq -e '
    (.persistenceProgress | length) > 0
    and (.accountProgress | length) > 0
    and (.quickfixProgress | length) > 0
    and .persistenceQuarantines == 0
    and .accountQuarantines == 0
    and .quickfixQuarantines == 0
    and .quickfixPendingIntents == 0
  ' "$state" >/dev/null ||
    die 'baseline requires processed traffic and healthy critical consumers'
}

wait_workloads
capture_events_offsets "$evidence_dir/before/matching-events-offsets.json"
capture_database_state "$evidence_dir/before/database-state.json"
require_healthy_state "$evidence_dir/before/database-state.json"

capture_pod_uids app.kubernetes.io/name=account-service \
  "$evidence_dir/before/account-pod-uids.json"
capture_pod_uids app.kubernetes.io/name=persistence \
  "$evidence_dir/before/persistence-pod-uids.json"
capture_pod_uids app.kubernetes.io/name=quickfix-gateway \
  "$evidence_dir/before/quickfix-pod-uids.json"

kns rollout restart deployment/account-service deployment/persistence >/dev/null
kns rollout restart statefulset/quickfix-gateway >/dev/null
wait_workloads
wait_all_pods_replaced app.kubernetes.io/name=account-service \
  "$evidence_dir/before/account-pod-uids.json" \
  "$evidence_dir/after-consumers/account-pod-uids.json"
wait_all_pods_replaced app.kubernetes.io/name=persistence \
  "$evidence_dir/before/persistence-pod-uids.json" \
  "$evidence_dir/after-consumers/persistence-pod-uids.json"
wait_all_pods_replaced app.kubernetes.io/name=quickfix-gateway \
  "$evidence_dir/before/quickfix-pod-uids.json" \
  "$evidence_dir/after-consumers/quickfix-pod-uids.json"
capture_database_state "$evidence_dir/after-consumers/database-state.json"
require_same_json \
  "$evidence_dir/before/database-state.json" \
  "$evidence_dir/after-consumers/database-state.json" \
  'durable consumer state changed during consumer Pod replacement'

postgres="$(postgres_pod)"
postgres_uid="$(kns get pod "$postgres" -o jsonpath='{.metadata.uid}')"
kns delete pod "$postgres" --wait=false \
  >"$evidence_dir/after-postgres/delete.txt"
wait_named_pod_replaced "$postgres" "$postgres_uid" PostgreSQL
wait_workloads
capture_database_state "$evidence_dir/after-postgres/database-state.json"
capture_events_offsets "$evidence_dir/after-postgres/matching-events-offsets.json"
require_same_json \
  "$evidence_dir/before/database-state.json" \
  "$evidence_dir/after-postgres/database-state.json" \
  'durable consumer state changed during PostgreSQL Pod replacement'
require_same_json \
  "$evidence_dir/before/matching-events-offsets.json" \
  "$evidence_dir/after-postgres/matching-events-offsets.json" \
  'matching.events advanced during the no-traffic PostgreSQL restart'

broker="$(kafka_pod)"
broker_uid="$(kns get pod "$broker" -o jsonpath='{.metadata.uid}')"
kns delete pod "$broker" --wait=false >"$evidence_dir/after-kafka/delete.txt"
wait_named_pod_replaced "$broker" "$broker_uid" Kafka
wait_workloads
capture_database_state "$evidence_dir/after-kafka/database-state.json"
capture_events_offsets "$evidence_dir/after-kafka/matching-events-offsets.json"
require_same_json \
  "$evidence_dir/before/database-state.json" \
  "$evidence_dir/after-kafka/database-state.json" \
  'durable consumer state changed during Kafka broker replacement'
require_same_json \
  "$evidence_dir/before/matching-events-offsets.json" \
  "$evidence_dir/after-kafka/matching-events-offsets.json" \
  'matching.events advanced during the no-traffic Kafka restart'

jq -n \
  --arg status PASS \
  --arg namespace "$namespace" \
  --arg generatedAtUtc "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
  '{
    status:$status,
    namespace:$namespace,
    generatedAtUtc:$generatedAtUtc,
    claim:"durable restart behavior without admitted traffic",
    proven:[
      "Account consumer Pods reconstructed durable progress",
      "Persistence consumer Pods reconstructed durable progress",
      "QuickFIX Gateway StatefulSet reconstructed durable progress",
      "PostgreSQL Pod replacement preserved consumer state",
      "one Kafka broker Pod replacement preserved consumer state",
      "matching.events offsets stayed stable during no-traffic restarts",
      "no unresolved quarantine or pending FIX intent remained"
    ],
    notProven:[
      "event retention while PostgreSQL and consumers are unavailable",
      "FIX retained-session retransmission across Gateway restart"
    ]
  }' >"$evidence_dir/verdict.json"

printf 'Critical consumer restart certification passed: %s\n' \
  "$evidence_dir/verdict.json"
