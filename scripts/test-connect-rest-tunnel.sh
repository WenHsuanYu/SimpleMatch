#!/usr/bin/env bash
set -Eeuo pipefail
IFS=$'\n\t'

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/lib/connect-rest-tunnel.sh
source "$script_dir/lib/connect-rest-tunnel.sh"

fail() {
  printf 'Connect REST tunnel contract failed: %s\n' "$*" >&2
  exit 1
}

fixture_dir="$(mktemp -d "${TMPDIR:-/tmp}/simplematch-connect-tunnel.XXXXXX")"
trap 'connect_rest_tunnel_close 2 || true; rm -rf -- "$fixture_dir"' EXIT
fake_bin="$fixture_dir/bin"
mkdir -p "$fake_bin"

cat >"$fake_bin/kubectl" <<'EOF_KUBECTL'
#!/usr/bin/env bash
set -euo pipefail
count=0
[[ ! -f "$TUNNEL_TEST_DIR/kubectl-count" ]] ||
  count="$(<"$TUNNEL_TEST_DIR/kubectl-count")"
count=$((count + 1))
printf '%s\n' "$count" >"$TUNNEL_TEST_DIR/kubectl-count"
printf 'Forwarding from 127.0.0.1:%s -> 8083\n' "$((31000 + count))"
trap 'exit 0' TERM INT
while true; do sleep 1; done
EOF_KUBECTL

cat >"$fake_bin/curl" <<'EOF_CURL'
#!/usr/bin/env bash
set -euo pipefail
if [[ "${TUNNEL_TEST_SLOW:-false}" == true ]]; then
  sleep 2
  exit 7
fi
count=0
[[ ! -f "$TUNNEL_TEST_DIR/curl-count" ]] ||
  count="$(<"$TUNNEL_TEST_DIR/curl-count")"
count=$((count + 1))
printf '%s\n' "$count" >"$TUNNEL_TEST_DIR/curl-count"
((count > 1)) || exit 7
url="${*: -1}"
[[ "$url" == \
  'http://127.0.0.1:31002/connectors/account-service-outbox/status' ]] || exit 22
printf '%s\n' \
  '{"connector":{"state":"RUNNING"},"tasks":[{"id":0,"state":"RUNNING"}]}'
EOF_CURL
chmod +x "$fake_bin/kubectl" "$fake_bin/curl"

export TUNNEL_TEST_DIR="$fixture_dir"
CONNECT_REST_TUNNEL_KUBECTL_BIN="$fake_bin/kubectl"
CONNECT_REST_TUNNEL_CURL_BIN="$fake_bin/curl"
log_path="$fixture_dir/connect-port-forward.log"
printf '%s\n' 'Forwarding from 127.0.0.1:39999 -> 8083' >"$log_path"

connect_rest_tunnel_configure kind-simplematch-live simplematch-cert-run "$log_path" ||
  fail 'valid tunnel configuration was rejected'
status="$(connect_rest_tunnel_status account-service-outbox 5)" ||
  fail 'REST failure was not recovered through a fresh tunnel'
jq -e '.connector.state == "RUNNING" and .tasks[0].id == 0' <<<"$status" \
  >/dev/null || fail 'connector status was not returned unchanged'
[[ "$(<"$fixture_dir/kubectl-count")" == 2 ]] ||
  fail 'REST recovery did not replace the failed tunnel exactly once'
[[ "$(<"$fixture_dir/curl-count")" == 2 ]] ||
  fail 'REST recovery did not retry the status request exactly once'
grep -Fq 'Restarting Kafka Connect service port-forward after REST failure' \
  "$log_path" || fail 'REST recovery was not recorded in the tunnel log'

tunnel_pid="$CONNECT_REST_TUNNEL_PID"
connect_rest_tunnel_configure kind-simplematch-live simplematch-cert-run "$log_path" ||
  fail 'reconfiguration did not close the previous tunnel'
if kill -0 "$tunnel_pid" >/dev/null 2>&1; then
  fail 'reconfiguration retained the previous tunnel process'
fi

tunnel_pid="$CONNECT_REST_TUNNEL_PID"
connect_rest_tunnel_close 2 || fail 'tunnel close exceeded its bounded budget'
[[ -z "$CONNECT_REST_TUNNEL_PID" && -z "$CONNECT_REST_TUNNEL_URL" ]] ||
  fail 'tunnel state was retained after close'
if kill -0 "$tunnel_pid" >/dev/null 2>&1; then
  fail 'tunnel process remained alive after close'
fi

CONNECT_REST_TUNNEL_PID="$$"
CONNECT_REST_TUNNEL_PID_START=not-the-current-process
connect_rest_tunnel_close 2 || fail 'stale PID identity was not safely discarded'
[[ -z "$CONNECT_REST_TUNNEL_PID" ]] || fail 'stale PID identity remained configured'

export TUNNEL_TEST_SLOW=true
started_at=$SECONDS
if connect_rest_tunnel_status account-service-outbox 1 >/dev/null 2>&1; then
  fail 'request exceeding the shared deadline unexpectedly passed'
fi
elapsed=$((SECONDS - started_at))
((elapsed <= 2)) || fail 'request retry exceeded the shared absolute deadline'
[[ "$(<"$fixture_dir/kubectl-count")" == 3 ]] ||
  fail 'expired request budget opened an additional retry tunnel'
unset TUNNEL_TEST_SLOW

if connect_rest_tunnel_configure '' simplematch-cert-run "$log_path"; then
  fail 'empty Kubernetes context was accepted'
fi
if connect_rest_tunnel_status '../connector' 5 >/dev/null 2>&1; then
  fail 'unsafe connector name was accepted'
fi

printf '%s\n' 'Connect REST tunnel contract passed.'
