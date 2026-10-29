#!/usr/bin/env bash
# Shared retained-runtime test data. Callers provide repo_root and source signatures.

focused_fixture_lib_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../../lib" && pwd)"
# shellcheck source=scripts/lib/local-image-inventory.sh
source "$focused_fixture_lib_dir/local-image-inventory.sh"
# shellcheck source=scripts/lib/local-image-transport.sh
source "$focused_fixture_lib_dir/local-image-transport.sh"
# shellcheck source=scripts/lib/local-certification-provenance.sh
source "$focused_fixture_lib_dir/local-certification-provenance.sh"
# shellcheck source=scripts/lib/local-certification-phase-graph.sh
source "$focused_fixture_lib_dir/local-certification-phase-graph.sh"
# shellcheck source=scripts/lib/local-certification-evidence.sh
source "$focused_fixture_lib_dir/local-certification-evidence.sh"
# shellcheck source=scripts/lib/local-certification-images.sh
source "$focused_fixture_lib_dir/local-certification-images.sh"
# shellcheck source=scripts/lib/local-certification-focused-diagnostic.sh
source "$focused_fixture_lib_dir/local-certification-focused-diagnostic.sh"
unset focused_fixture_lib_dir

encode() {
  printf '%s' "$1" | base64 | tr -d '\n'
}

write_lock() {
  local lock_file="$1"
  local image_class service build_source repository digest

  : >"$lock_file"
  while IFS='|' read -r _ service _ repository; do
    digest="$(printf '%s' "$service" | sha256sum | awk '{print $1}')"
    printf '%s|%s|%s|%s\n' \
      "$service" "$repository:focused" \
      "localhost:5001/$repository:focused" \
      "localhost:5001/$repository@sha256:$digest" >>"$lock_file"
  done < <(simplematch_local_image_inventory_entries)
}

write_workload_json() {
  local lock_file="$1"
  local output_file="$2"
  local service reference workload_name items='[]'

  while IFS='|' read -r service _ _ reference; do
    workload_name="$service"
    [[ "$service" == flyway-runner ]] && workload_name=account-service-flyway
    items="$(jq --arg name "$workload_name" --arg image "$reference" \
      '. + [{kind:"Deployment", metadata:{name:$name},
        spec:{template:{spec:{containers:[{name:$name,image:$image}]}}}}]' \
      <<<"$items")"
  done <"$lock_file"
  items="$(jq '. + [{kind:"Deployment",metadata:{name:"kafka-connect"},
    spec:{template:{spec:{containers:[{name:"kafka-connect",
      image:"quay.io/debezium/connect:3.6.0.Final"}]}}}}]' <<<"$items")"
  jq -n --argjson items "$items" '{apiVersion:"v1",kind:"List",items:$items}' >"$output_file"
}

write_secrets_json() {
  local output_file="$1"
  local dsn='postgresql://simplematch:simplematch@postgres:5432/simplematch'
  local secrets='[]' service

  secrets="$(jq --arg dsn "$(encode "$dsn")" \
    '. + [{metadata:{name:"simplematch-flyway-secrets"},data:{postgres_dsn:$dsn}}]' \
    <<<"$secrets")"
  secrets="$(jq --arg user "$(encode simplematch)" \
    --arg password "$(encode simplematch)" \
    '. + [{metadata:{name:"simplematch-postgres-secrets"},
      data:{postgres_user:$user,postgres_password:$password}}]' <<<"$secrets")"
  for service in \
      account-service risk-service persistence market-data-projection \
      query-service quickfix-gateway; do
    secrets="$(jq --arg name "${service}-secrets" \
      --arg dsn "$(encode "$dsn")" \
      '. + [{metadata:{name:$name},data:{postgres_dsn:$dsn}}]' <<<"$secrets")"
  done
  secrets="$(jq --arg policy "$(encode 'synthetic-approved-policy')" \
    --arg store "$(encode 'synthetic-approved-store')" '
    map(if .metadata.name == "query-service-secrets" then
      .immutable=true | .data["query-service.properties"]=$policy
      elif .metadata.name == "quickfix-gateway-secrets" then
      .immutable=true | .data["quickfix-gateway.properties"]=$policy else . end)
    + [{metadata:{name:"quickfix-gateway-fix-tls"},immutable:true,
        data:{"server.p12":$store,"clients.p12":$store}}]' <<<"$secrets")"
  jq -n --argjson items "$secrets" '{apiVersion:"v1",kind:"SecretList",items:$items}' >"$output_file"
}

write_fake_kubectl() {
  local fake_bin="$1"
  cat >"$fake_bin" <<'EOF_KUBECTL'
#!/usr/bin/env bash
set -Eeuo pipefail
root="${FAKE_KUBECTL_ROOT:?FAKE_KUBECTL_ROOT is required}"
case "$*" in
  *'config current-context'*) cat "$root/current-context" ;;
  *'get namespace'*) cat "$root/namespace.json" ;;
  *'get deployments,statefulsets,jobs'*) cat "$root/workloads.json" ;;
  *'get configmap matching-session-config'*) cat "$root/matching-session-config.json" ;;
  *'get configmap matching-daily-artifact'*) cat "$root/matching-daily-artifact.json" ;;
  *'get configmap quickfix-gateway-fix-spec'*) cat "$root/quickfix-gateway-fix-spec.json" ;;
  *'get configmap risk-service-config'*) cat "$root/risk-service-config.json" ;;
  *'get secrets'*) cat "$root/secrets.json" ;;
  *)
    printf 'unexpected fake kubectl invocation: %s\n' "$*" >&2
    exit 1
    ;;
esac
EOF_KUBECTL
  chmod 755 "$fake_bin"
}

write_fake_observer() {
  local fake_bin="$1"
  cat >"$fake_bin" <<'EOF_OBSERVER'
#!/usr/bin/env bash
set -Eeuo pipefail
output=''
while (($# > 0)); do
  case "$1" in
    --evidence-dir) output="$2"; shift 2 ;;
    *) shift ;;
  esac
done
printf '%s\n' invoked >"${FAKE_OBSERVER_MARKER:?FAKE_OBSERVER_MARKER is required}"
mkdir -p "$output"
jq -n '{status:"PASS"}' >"$output/verdict.json"
EOF_OBSERVER
  chmod 755 "$fake_bin"
}

write_fake_verifier_contract() {
  local fake_bin="$1"
  printf '%s\n' 'printf "%s\\n" helper-loaded' >"${fake_bin}.helper"
  cat >"$fake_bin" <<'EOF_CONTRACT'
#!/usr/bin/env bash
set -Eeuo pipefail
helper="${BASH_SOURCE[0]}.helper"
[[ -f "$helper" ]] || exit 1
source "$helper"
printf '%s\n' invoked >"${FAKE_VERIFIER_CONTRACT_MARKER:?FAKE_VERIFIER_CONTRACT_MARKER is required}"
printf '%s\n' 'CDC observer fixture header contract is valid.'
EOF_CONTRACT
  chmod 755 "$fake_bin"
}

write_dependencies() {
  local evidence_dir="$1" dependency result_file source_revision

  image_transport=registry
  image_tag=focused
  skip_build=false
  skip_compose=false
  skip_kubernetes=false
  matching_fleet_only=false
  SIMPLEMATCH_FOCUSED_DEPENDENCIES=()
  declare -gA SIMPLEMATCH_FOCUSED_DEPENDENCY_SEEN=()
  simplematch_focused_collect_dependencies kubernetes-cdc-delivery || return 1
  source_revision="$(git -C "$repo_root" rev-parse HEAD)"
  for dependency in "${SIMPLEMATCH_FOCUSED_DEPENDENCIES[@]}"; do
    result_file="$evidence_dir/phases/$dependency/result.json"
    mkdir -p "$(dirname -- "$result_file")"
    jq -n --arg phase "$dependency" --arg source "$source_revision" \
      --arg signature "$source_signature" \
      '{schemaVersion:2,runId:"20260903-123456-1",phaseId:$phase,
        status:"PASS",exitCode:0,sourceSignature:$signature,
        execution:{sourceRevision:$source}}' >"$result_file"
  done
  sha256sum "$evidence_dir/local-images.lock" | awk '{print $1}' >"$evidence_dir/image-lock.sha256"
}

write_fixture() {
  local evidence_dir="$1"
  local source_signature="$2"
  local runtime_signature="${3:-$current_runtime_signature}"
  local verifier_signature="${4:-$current_verifier_signature}"
  local profile_override="${5:-}"
  local image_lock matching_digest

  mkdir -p "$evidence_dir"
  image_lock="$evidence_dir/local-images.lock"
  write_lock "$image_lock"
  matching_digest="$(simplematch_local_image_lock_digest "$image_lock" matching)"
  printf '%s\n' kind-simplematch-live >"$evidence_dir/current-context"
  jq -n '{metadata:{labels:{
    "simplematch.io/lifecycle":"disposable",
    "simplematch.io/managed-by":"local-production-like-certification",
    "simplematch.io/run-id":"20260903-123456-1"}}}' >"$evidence_dir/namespace.json"
  printf '%s\n' "run_id=20260903-123456-1" \
    "namespace=simplematch-local-cert-focused" \
    'cluster=simplematch-live' \
    'trading_day=2026-08-27' \
    'image_tag=focused' \
    'image_transport=registry' \
    "source_signature=$source_signature" \
    "cdc_runtime_signature=$runtime_signature" \
    "cdc_verifier_signature=$verifier_signature" \
    'query_security_input_digest=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa' \
    "skip_build=${profile_override:-false}" \
    'skip_compose=false' \
    'skip_kubernetes=false' \
    'matching_fleet_only=false' >"$evidence_dir/run-context"
  jq -n --arg digest "$matching_digest" '{immutable:true,data:{
    trading_day:"2026-08-27",trading_session_id:"2026-08-27-regular",
    matching_image_digest:$digest}}' >"$evidence_dir/matching-session-config.json"
  jq -n '{immutable:true,data:{artifact:"fixture"}}' >"$evidence_dir/matching-daily-artifact.json"
  jq -n '{immutable:true,data:{fix:"fixture"}}' >"$evidence_dir/quickfix-gateway-fix-spec.json"
  jq -n '{data:{"application.yaml":"simplematch:\n  maximum-metric-age: 60s\n"}}' \
    >"$evidence_dir/risk-service-config.json"
  write_secrets_json "$evidence_dir/secrets.json"
  local security_digest
  security_digest="$(python3 "$repo_root/scripts/end-to-end/query-service/lib/certification_security.py" \
    --observed-fingerprint <"$evidence_dir/secrets.json")"
  printf 'query_security_deployed_digest=%s\n' "$security_digest" >>"$evidence_dir/run-context"
  write_workload_json "$image_lock" "$evidence_dir/workloads.json"
  write_dependencies "$evidence_dir"
  printf '%s\n' "$(git -C "$repo_root" rev-parse HEAD)" \
    >"$evidence_dir/source-revision"
}


# A complete same-source retained run for the placement command's stricter gate.
write_complete_fixture() {
  local evidence_dir="$1" source_signature="$2" phase required
  local run_id=20260903-123456-1
  write_fixture "$evidence_dir" "$source_signature"
  required="$(certification_required_phase_ids)"
  while IFS= read -r phase; do
    certification_write_phase_result "$phase" PASS 0
  done <<<"$required"
  certification_write_completion_manifest "$evidence_dir" "$required"
  printf '%s\n' '- status: PASSED' >"$evidence_dir/report.md"
  printf 'sha256:%064d\n' 0 >"$evidence_dir/verifier-image-identity"
  printf 'localhost:5001/fixture@sha256:%064d\n' 0 >"$evidence_dir/verifier-image-reference"
}
