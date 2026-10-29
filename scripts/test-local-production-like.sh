#!/usr/bin/env bash
set -Eeuo pipefail
IFS=$'\n\t'

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd -- "$script_dir/.." && pwd)"
# shellcheck source=scripts/lib/local-certification-kubernetes.sh
source "$repo_root/scripts/lib/local-certification-kubernetes.sh"

fixture_dir="$(mktemp -d)"
trap 'rm -rf -- "$fixture_dir"' EXIT

die() {
  printf '%s\n' "$*" >&2
  exit 1
}

# Observe the ConfigMap delivered to kubectl, including rejection before later inputs.
SIMPLEMATCH_MARKET_REFERENCE_DELIVERY_MANIFEST="$fixture_dir/delivery.yaml"
input_manifest="$fixture_dir/fix-spec.yaml"
cat >"$SIMPLEMATCH_MARKET_REFERENCE_DELIVERY_MANIFEST" <<'EOF_DELIVERY'
apiVersion: v1
kind: ConfigMap
metadata:
  name: market-reference-20261028-fixture
immutable: true
EOF_DELIVERY
printf '%s\n' 'kind: ConfigMap' >"$input_manifest"

kubectl() {
  local IFS=' '
  local payload
  if [[ "$*" == *"create -f $input_manifest --dry-run=client -o json" ]]; then
    printf '%s\n' '{"kind":"ConfigMap","metadata":{"name":"quickfix-gateway-fix-spec"}}'
  elif [[ "$*" == *'-f -'* ]]; then
    payload="$(cat)"
    if [[ "$(jq -r '.metadata.name // ""' <<<"$payload")" == quickfix-gateway-fix-spec ]]; then
      [[ "$*" == *'create -f -' && "$(jq -r .immutable <<<"$payload")" == true ]] || return 41
      [[ "$reject_dictionary" == false ]] || return 42
      printf '%s\n' "$payload" >"$fixture_dir/created-dictionary.json"
    fi
  elif [[ "$*" == *'create configmap matching-session-config'* ]]; then
    touch "$fixture_dir/later-input"
    printf '%s\n' '{"kind":"ConfigMap","metadata":{"name":"matching-session-config"}}'
  elif [[ "$*" == *'--dry-run=client'* ]]; then
    printf '%s\n' '{"kind":"Secret","metadata":{"name":"fixture"}}'
  fi
}

create_inputs() {
  certification_trading_day=2026-10-28 namespace=certification-input-contract \
    kind_context=contract matching_fleet_only=true evidence_dir="$fixture_dir/evidence" \
    local_postgres_dsn=fixture-dsn local_postgres_password=fixture-password \
    apply_local_kubernetes_inputs sha256:fixture "$input_manifest"
}

reject_dictionary=false
create_inputs
[[ -s "$fixture_dir/created-dictionary.json" && -f "$fixture_dir/later-input" ]]
rm "$fixture_dir/created-dictionary.json" "$fixture_dir/later-input"
reject_dictionary=true
if create_inputs; then
  die 'Dictionary creation failure was accepted.'
fi
[[ ! -e "$fixture_dir/later-input" ]] || die 'Inputs continued after dictionary creation failed.'
unset -f kubectl

printf '%s\n' 'Local production-like input contracts are valid.'

# Exercise the complete placement command with real Java and HTTP adapters.
# Kubernetes and Docker commands observe an isolated synthetic environment.
# shellcheck source=scripts/testdata/focused-diagnostic/fixture.sh
source "$script_dir/testdata/focused-diagnostic/fixture.sh"
export SIMPLEMATCH_LOCAL_REGISTRY_PORT=5001
source_signature="$(simplematch_certification_source_signature "$repo_root")"
current_runtime_signature="$(simplematch_certification_cdc_runtime_signature "$repo_root")"
current_verifier_signature="$(simplematch_certification_cdc_verifier_signature "$repo_root")"
write_complete_fixture "$fixture_dir/retained" "$source_signature"
mkdir "$fixture_dir/bin"
for tool in kubectl kind docker curl; do
  ln -s "$script_dir/testdata/recovery-runners/command.rb" "$fixture_dir/bin/$tool"
done
write_fake_kubectl "$fixture_dir/base-kubectl"
python3 "$script_dir/end-to-end/query-service/provision-security-fixtures.py" \
  "$fixture_dir/private" >/dev/null
python3 "$script_dir/testdata/recovery-runners/http-fixture.py" \
  "$fixture_dir/private" "$fixture_dir/http-port" &
http_pid=$!
trap 'kill "$http_pid" 2>/dev/null || true; wait "$http_pid" 2>/dev/null || true; rm -rf -- "$fixture_dir"' EXIT
for _ in {1..50}; do
  [[ -s "$fixture_dir/http-port" ]] && break
  kill -0 "$http_pid" 2>/dev/null || { printf "HTTP fixture exited early.\n" >&2; exit 1; }
  sleep 0.1
done
[[ -s "$fixture_dir/http-port" ]] || die "HTTP fixture did not start."
if ! PATH="$fixture_dir/bin:$PATH" RECOVERY_TEST_ROOT="$fixture_dir" \
    RECOVERY_TEST_MODE=placement \
    RECOVERY_TEST_DATA="$repo_root/tools/recovery-evidence-verifier/src/test/resources/placement" \
    RECOVERY_TEST_QUERY_IMAGE="$(simplematch_local_image_lock_digest_reference "$fixture_dir/retained/local-images.lock" query-service)" \
    RECOVERY_TEST_HTTP_PORT="$(cat "$fixture_dir/http-port")" FAKE_KUBECTL_ROOT="$fixture_dir/retained" \
    bash "$script_dir/run-local-java-placement-serving-check.sh" \
    --namespace simplematch-local-cert-focused --namespace-run-id 20260903-123456-1 \
    --retained-evidence-dir "$fixture_dir/retained" --evidence-dir "$fixture_dir/placement-run" \
    --query-fixture-directory "$fixture_dir/private" --timeout-seconds 180 \
    >"$fixture_dir/placement-output" 2>&1; then
  cat "$fixture_dir/placement-output" >&2; exit 1
fi
jq -e '.status == "PASS" and .schema_version == 3' \
  "$fixture_dir/placement-run/java-placement-serving.json" >/dev/null
[[ -f "$fixture_dir/redis-restored" && -f "$fixture_dir/tunnel-closed" && ! -e "$fixture_dir/redis-down" ]]
printf '%s\n' 'Complete placement command and restoration passed.'
