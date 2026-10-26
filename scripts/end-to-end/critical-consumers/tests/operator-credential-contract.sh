#!/usr/bin/env bash
# Observe only the process transport boundary; this is not an operator authorization simulator.
set -Eeuo pipefail
umask 077
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
temporary="$(mktemp -d)"
trap 'rm -rf -- "$temporary"' EXIT
export CAPTURE_DIRECTORY="$temporary"
source "$root/scripts/end-to-end/critical-consumers/lib/test-interfaces.sh"
kns() {
  printf '%s\n' "$@" >>"$temporary/kubectl-argv.txt"
  case "$1" in
    create) cat >"$temporary/secret.json" ;;
    patch) cat >"$temporary/patch.json" ;;
    *) return 1 ;;
  esac
}
gateway_operator_token="$(cat /proc/sys/kernel/random/uuid)"
set_certification_operator_env deployment/query-service \
  SIMPLEMATCH_QUERY_SERVICE_REBUILD_OPERATOR_TOKEN "$gateway_operator_token"
cat >"$temporary/curl" <<'PY'
#!/usr/bin/env python3
import json,os,pathlib,sys
root=pathlib.Path(os.environ['CAPTURE_DIRECTORY'])
(root/'curl-argv.json').write_text(json.dumps(sys.argv[1:]))
(root/'curl-stdin.txt').write_text(sys.stdin.read())
print('200')
PY
chmod 700 "$temporary/curl"
export PATH="$temporary:$PATH"
gateway_port=1
gateway_request GET /operations/status "$temporary/response.json"
python3 - <<'PY'
import json,os,pathlib
root=pathlib.Path(os.environ['CAPTURE_DIRECTORY'])
secret=json.loads((root/'secret.json').read_text())
variable='SIMPLEMATCH_QUERY_SERVICE_REBUILD_OPERATOR_TOKEN'
token=secret['stringData'][variable]
assert secret['immutable'] is True
reference=json.loads((root/'patch.json').read_text())['spec']['template']['spec']['containers'][0]['env'][0]
assert reference['valueFrom']['secretKeyRef']=={'name':secret['metadata']['name'],'key':variable}
assert token not in (root/'kubectl-argv.txt').read_text()
assert token not in (root/'curl-argv.json').read_text()
assert token in (root/'curl-stdin.txt').read_text()
PY
printf '%s\n' 'Operator credentials cross process transports through stdin and Secret references.'
