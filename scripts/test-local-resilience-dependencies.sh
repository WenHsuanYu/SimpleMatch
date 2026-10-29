#!/usr/bin/env bash
set -Eeuo pipefail
IFS=$'\n\t'

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/lib/local-kind.sh
source "$script_dir/lib/local-kind.sh"
# shellcheck source=scripts/lib/local-resilience-dependencies.sh
source "$script_dir/lib/local-resilience-dependencies.sh"

fail() {
  printf 'Local dependency resilience contract failed: %s\n' "$*" >&2
  exit 1
}

fixture_dir="$(mktemp -d "${TMPDIR:-/tmp}/simplematch-dependency-resilience.XXXXXX")"
trap 'rm -rf -- "$fixture_dir"' EXIT

ready_true='{"status":{"conditions":[{"type":"Ready","status":"True"}]}}'
ready_false='{"status":{"conditions":[{"type":"Ready","status":"False"}]}}'
ready_unknown='{"status":{"conditions":[{"type":"Ready","status":"Unknown"}]}}'
ready_missing='{"status":{"conditions":[{"type":"MemoryPressure","status":"False"}]}}'
ready_ambiguous='{"status":{"conditions":[{"type":"Ready","status":"False"},{"type":"Ready","status":"Unknown"}]}}'
ready_conflicting='{"status":{"conditions":[{"type":"Ready","status":"True"},{"type":"Ready","status":"Unknown"}]}}'
ready_unsupported='{"status":{"conditions":[{"type":"Ready","status":"Maybe"}]}}'
[[ "$(simplematch_kind_node_readiness_state "$ready_true")" == true ]] ||
  fail 'Ready=True was not parsed as healthy'
[[ "$(simplematch_kind_node_readiness_state "$ready_false")" == false ]] ||
  fail 'Ready=False was not parsed as unavailable'
[[ "$(simplematch_kind_node_readiness_state "$ready_unknown")" == unknown ]] ||
  fail 'Ready=Unknown was not parsed as unavailable'
if simplematch_kind_node_readiness_state "$ready_missing" >/dev/null 2>&1; then
  fail 'missing Ready condition unexpectedly passed'
fi
if simplematch_kind_node_readiness_state "$ready_ambiguous" >/dev/null 2>&1; then
  fail 'ambiguous Ready condition unexpectedly passed'
fi
if simplematch_kind_node_readiness_state "$ready_conflicting" >/dev/null 2>&1; then
  fail 'conflicting Ready conditions unexpectedly passed'
fi
if simplematch_kind_node_readiness_state "$ready_unsupported" >/dev/null 2>&1; then
  fail 'unsupported Ready status unexpectedly passed'
fi

fake_bin="$fixture_dir/bin"
mkdir -p "$fake_bin"
namespace_fixture="$fixture_dir/namespace.json"
nodes_fixture="$fixture_dir/nodes.json"
jq -n '{items: [
  {metadata:{name:"control-plane",labels:{"node-role.kubernetes.io/control-plane":""}}},
  (range(3) | {metadata:{name:("worker-" + tostring),
    labels:{"simplematch.io/node-pool":"local-resilience"}},
    status:{conditions:[{type:"Ready",status:"True"}]}})
]}' >"$nodes_fixture"
cat >"$fake_bin/kubectl" <<'EOF_KUBECTL'
#!/usr/bin/env bash
set -euo pipefail
if [[ -n "${RESILIENCE_TEST_EVENTS:-}" ]]; then
  printf 'kubectl %s\n' "$*" >>"$RESILIENCE_TEST_EVENTS"
fi
argument_after() {
  local previous='' argument
  local key="$1"
  shift
  for argument in "$@"; do
    [[ "$previous" != "$key" ]] || { printf '%s\n' "$argument"; return; }
    previous="$argument"
  done
  return 1
}
emit_pods() {
  local component="$1" name="${2:-}" stage=before
  [[ ! -f "$RESILIENCE_TEST_EVENTS.fault" ]] || stage=after
  jq --arg component "$component" --arg name "$name" --arg stage "$stage" '
    def pod: {metadata:{name:.pod,uid:.pod_uid,
      labels:{"apps.kubernetes.io/pod-index":((.node_id // 0)|tostring)}},
      spec:{nodeName:.node,volumes:[{name:(if $component == "postgresql"
        then "postgres-data" else "kafka-data" end),persistentVolumeClaim:{claimName:.pvc}}]},
      status:{conditions:[{type:"Ready",status:"True"}]}};
    (if $component == "postgresql" then [.target[$stage]] else .brokers_before end)
    | map(pod) | if $name == "" then {items:.}
      else .[] | select(.metadata.name == $name) end
  ' "$RESILIENCE_TEST_REPORTS/$component.json"
}
storage_identity() {
  local kind="$1" name="$2"
  jq -n --arg kind "$kind" --arg name "$name" \
    --slurpfile postgres "$RESILIENCE_TEST_REPORTS/postgresql.json" \
    --slurpfile kafka "$RESILIENCE_TEST_REPORTS/kafka.json" '
    ([$postgres[0].target.before] + $kafka[0].brokers_before)[]
    | select(if $kind == "pvc" then .pvc == $name else .pv == $name end)
    | if $kind == "pvc" then {status:{phase:"Bound"},spec:{volumeName:.pv}}
      else {spec:{nodeAffinity:{required:{nodeSelectorTerms:[{matchExpressions:[{
        key:"kubernetes.io/hostname",values:[.node]}]}]}}}} end
  '
}
case "$*" in
  'config get-contexts -o name') printf '%s\n' kind-simplematch-live ;;
  *'get nodes -o json') cat "$RESILIENCE_TEST_NODES" ;;
  *'get namespace '*'-o json') cat "$RESILIENCE_TEST_NAMESPACE" ;;
  *'get pods -l app.kubernetes.io/name=postgres '*) emit_pods postgresql ;;
  *'get pod postgres-0 -o json'*) emit_pods postgresql postgres-0 ;;
  *'get pods -l app.kubernetes.io/name=kafka,'*) emit_pods kafka ;;
  *'get pod kafka-'*) emit_pods kafka "$(argument_after pod "$@")" ;;
  *'get pvc '*) storage_identity pvc "$(argument_after pvc "$@")" ;;
  *'get pv '*) storage_identity pv "$(argument_after pv "$@")" ;;
  *'get node '*)
    node="$(argument_after node "$@")"
    jq -n --arg name "$node" '{metadata:{name:$name,labels:{
      "simplematch.io/node-pool":"local-resilience","simplematch.io/worker-slot":
      (if $name|endswith("worker2") then "1" elif $name|endswith("worker3") then "2" else "0" end)}}}'
    ;;
  *'delete pod postgres-0 '*) touch "$RESILIENCE_TEST_EVENTS.fault" ;;
  *'exec '*'/meta.properties'*)
    pod="$(argument_after exec "$@")"
    printf 'cluster.id=5L6g3nShT-eMCtK--X86sw\nnode.id=%s\n' "${pod#kafka-}"
    ;;
  *'exec '*'psql '*)
    sql="${*: -1}"
    if [[ "$sql" == *'DELETE FROM'* ]]; then
      printf '%s\n' marker-cleanup-failed >>"$RESILIENCE_TEST_EVENTS"
      exit 17
    fi
    [[ ! -f "$RESILIENCE_TEST_EVENTS.fault" ]] || \
      printf '%s\n' marker-observed-after-recovery >>"$RESILIENCE_TEST_EVENTS"
    printf '1\n'
    ;;
  *'kafka-topics.sh '*|*'kafka-configs.sh '*)
    if [[ "$*" == *'--list'* ]]; then
      [[ ! -f "$RESILIENCE_TEST_EVENTS.topic" ]] || cat "$RESILIENCE_TEST_EVENTS.topic"
    elif [[ "$*" == *'--create'* ]]; then
      argument_after --topic "$@" >"$RESILIENCE_TEST_EVENTS.topic"
      exit 17
    elif [[ "$*" == *'--describe'* ]]; then
      topic="$(argument_after --topic "$@" || argument_after --entity-name "$@")"
      partitions=15; policy=delete
      case "$topic" in
        simplematch-connect-configs) partitions=1; policy=compact ;;
        simplematch-connect-offsets) partitions=25; policy=compact ;;
        simplematch-connect-status) partitions=3; policy=compact ;;
      esac
      printf 'PartitionCount: %s ReplicationFactor: 3 unclean.leader.election.enable=false cleanup.policy=%s min.insync.replicas=2 retention.ms=2592000000\n' \
        "$partitions" "$policy"
    else
      exit 19
    fi
    ;;
  *'get --raw=/readyz?verbose'*)
    printf '%s\n' 'readyz check passed'
    ;;
  *'get pods -n kube-system -o json'*)
    cat <<'EOF_PODS'
{"items":[
  {"metadata":{"name":"etcd-simplematch-live-control-plane"},"status":{"phase":"Running","conditions":[{"type":"Ready","status":"True"}],"containerStatuses":[{"restartCount":0}]}},
  {"metadata":{"name":"kube-controller-manager-simplematch-live-control-plane"},"status":{"phase":"Running","conditions":[{"type":"Ready","status":"True"}],"containerStatuses":[{"restartCount":0}]}},
  {"metadata":{"name":"kube-scheduler-simplematch-live-control-plane"},"status":{"phase":"Running","conditions":[{"type":"Ready","status":"True"}],"containerStatuses":[{"restartCount":0}]}}
]}
EOF_PODS
    ;;
  *'get events -n kube-system'*)
    printf '%s\n' '{"items":[]}'
    ;;
  *)
    printf 'unexpected fake kubectl invocation: %s\n' "$*" >&2
    exit 1
    ;;
esac
EOF_KUBECTL
chmod +x "$fake_bin/kubectl"
cat >"$fake_bin/docker" <<'EOF_DOCKER'
#!/usr/bin/env bash
set -euo pipefail
case "$*" in
  info) ;;
  inspect*'io.x-k8s.kind.cluster'*) printf '%s\n' simplematch-live ;;
  inspect*'io.x-k8s.kind.role'*) printf '%s\n' worker ;;
  *) printf 'unexpected Docker command: %s\n' "$*" >>"$RESILIENCE_TEST_EVENTS"; exit 19 ;;
esac
EOF_DOCKER
printf '%s\n' '#!/usr/bin/env bash' \
  '[[ "$*" == "get clusters" ]] && printf "%s\n" simplematch-live' >"$fake_bin/kind"
chmod +x "$fake_bin/docker" "$fake_bin/kind"
control_plane_evidence="$fixture_dir/control-plane"
PATH="$fake_bin:$PATH" simplematch_kind_validate_control_plane_stability \
  fake-context 1 5 "$control_plane_evidence" ||
  fail 'stable control-plane fixture was rejected'
[[ -s "$control_plane_evidence/readyz.txt" && -s "$control_plane_evidence/before.json" &&
  -s "$control_plane_evidence/after.json" && -s "$control_plane_evidence/events.json" ]] ||
  fail 'control-plane stability evidence was not retained'

redis_marker_value='run-redis-marker'
[[ "$(resilience_dependency_redis_marker_state "$redis_marker_value" "$redis_marker_value")" == present ]] ||
  fail 'matching Redis marker response was not classified as present'
[[ "$(resilience_dependency_redis_marker_state '' "$redis_marker_value")" == absent ]] ||
  fail 'empty Redis marker response was not classified as absent'
[[ "$(resilience_dependency_redis_marker_state '(nil)' "$redis_marker_value")" == absent ]] ||
  fail 'Redis nil marker response was not classified as absent'
if resilience_dependency_redis_marker_state unexpected "$redis_marker_value" >/dev/null 2>&1; then
  fail 'unexpected Redis marker response was accepted'
fi

runtime_script="$script_dir/run-local-resilience-dependencies.sh"
missing_run_id_output="$fixture_dir/missing-run-id.txt"
if "$runtime_script" --component postgresql --namespace simplematch-resilience-test \
    >"$missing_run_id_output" 2>&1; then
  fail 'fault injection without an exact namespace run-id unexpectedly passed'
fi
grep -Fq 'namespace-run-id is required for fault injection' "$missing_run_id_output" ||
  fail 'fault injection did not require an exact namespace run-id'

expect_diagnostic_rejection() {
  local label="$1" expected_message="$2" component="${3:-postgresql}"
  local output="$fixture_dir/$label.log"
  local evidence="$fixture_dir/$label"

  if PATH="$fake_bin:$PATH" RESILIENCE_TEST_NODES="$nodes_fixture" \
      RESILIENCE_TEST_NAMESPACE="$namespace_fixture" \
      RESILIENCE_TEST_EVENTS="$fixture_dir/$label.events" \
      RESILIENCE_TEST_REPORTS="$script_dir/../tools/recovery-evidence-verifier/src/test/resources" \
      "$runtime_script" --component "$component" --namespace simplematch-resilience-test \
      --namespace-run-id expected-run --context kind-simplematch-live \
      --fault-mode pod-restart --deadline-seconds 30 --evidence-dir "$evidence" >"$output" 2>&1; then
    fail "$label was accepted"
  fi
  grep -Fq "$expected_message" "$output" || fail "$label did not reach the expected rejection"
  [[ "$(jq -r .status "$evidence/$component.json")" == FAILED ]] ||
    fail "$label did not publish a failure report"
}

printf '%s\n' '{"metadata":{"labels":{}}}' >"$namespace_fixture"
expect_diagnostic_rejection unowned-namespace 'namespace is not an owned disposable'
jq -n '{metadata:{labels:{"simplematch.io/lifecycle":"disposable",
  "simplematch.io/managed-by":"local-resilience","simplematch.io/run-id":"another-run"}}}' \
  >"$namespace_fixture"
expect_diagnostic_rejection wrong-run 'namespace belongs to run another-run, not expected-run'

jq -n '{metadata:{labels:{"simplematch.io/lifecycle":"disposable",
  "simplematch.io/managed-by":"local-resilience","simplematch.io/run-id":"expected-run"}}}' \
  >"$namespace_fixture"
expect_diagnostic_rejection cleanup-failure 'could not clean up PostgreSQL durable marker'
grep -Fq marker-observed-after-recovery "$fixture_dir/cleanup-failure.events" ||
  fail 'cleanup fixture did not reach recovery observation'
grep -Fq marker-cleanup-failed "$fixture_dir/cleanup-failure.events" ||
  fail 'cleanup fixture did not reach cleanup failure'
observed_line="$(awk '/marker-observed-after-recovery/ {print NR; exit}' "$fixture_dir/cleanup-failure.events")"
cleanup_line="$(awk '/marker-cleanup-failed/ {print NR; exit}' "$fixture_dir/cleanup-failure.events")"
[[ "$observed_line" -lt "$cleanup_line" ]] || fail 'cleanup did not follow recovery observation'
expect_diagnostic_rejection uncertain-topic 'Kafka marker creation outcome is ambiguous' kafka
[[ "$(grep -c -- '--create' "$fixture_dir/uncertain-topic.events")" == 1 ]] ||
  fail 'uncertain marker creation was retried'
if grep -Eq -- '--delete|kafka-console-producer| delete |Docker command: (stop|start)' \
    "$fixture_dir/uncertain-topic.events"; then
  fail 'uncertain topic ownership allowed a mutation or cleanup'
fi

printf '%s\n' 'Local dependency resilience report contracts passed.'
