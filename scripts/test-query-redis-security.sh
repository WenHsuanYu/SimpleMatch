#!/usr/bin/env bash
set -euo pipefail
umask 077

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
redis_server="${SIMPLEMATCH_REDIS_SERVER:-redis-server}"
for executable in "$redis_server" openssl python3; do
  command -v "$executable" >/dev/null || {
    echo "Query Redis security test requires $executable" >&2
    exit 1
  }
done

# Fail before generating credentials or starting processes when loopback networking is unavailable.
port_lines="$(python3 - <<'PY'
import socket
from contextlib import ExitStack
with ExitStack() as stack:
    sockets = [stack.enter_context(socket.socket()) for _ in range(3)]
    for server in sockets:
        server.bind(('127.0.0.1', 0))
        print(server.getsockname()[1])
PY
)"
mapfile -t redis_ports <<<"$port_lines"
temporary_directory="$(mktemp -d "${TMPDIR:-/tmp}/simplematch-query-redis.XXXXXX")"
redis_pids=()
cleanup() {
  local pid
  for pid in "${redis_pids[@]}"; do
    kill "$pid" 2>/dev/null || true
    wait "$pid" 2>/dev/null || true
  done
  rm -rf "$temporary_directory"
}
trap cleanup EXIT

openssl req -x509 -newkey rsa:2048 -nodes -sha256 -days 1 -subj /CN=Query-Redis-Test-CA \
  -keyout "$temporary_directory/ca.key" -out "$temporary_directory/ca.crt" >/dev/null 2>&1
for identity in trusted mismatch; do
  hostname=localhost
  [[ "$identity" != mismatch ]] || hostname=wrong.example.invalid
  openssl req -new -newkey rsa:2048 -nodes -subj "/CN=$hostname" \
    -keyout "$temporary_directory/$identity.key" -out "$temporary_directory/$identity.csr" >/dev/null 2>&1
  printf 'subjectAltName=DNS:%s\nextendedKeyUsage=serverAuth\n' "$hostname" >"$temporary_directory/$identity.ext"
  openssl x509 -req -sha256 -days 1 -in "$temporary_directory/$identity.csr" \
    -CA "$temporary_directory/ca.crt" -CAkey "$temporary_directory/ca.key" -CAcreateserial \
    -extfile "$temporary_directory/$identity.ext" -out "$temporary_directory/$identity.crt" >/dev/null 2>&1
done
openssl req -x509 -newkey rsa:2048 -nodes -sha256 -days 1 -subj /CN=localhost \
  -addext subjectAltName=DNS:localhost -keyout "$temporary_directory/untrusted.key" \
  -out "$temporary_directory/untrusted.crt" >/dev/null 2>&1

export SIMPLEMATCH_REDIS_TEST_PASSWORD="$(openssl rand -hex 32)"
export SIMPLEMATCH_REDIS_TEST_ADMIN_PASSWORD="$(openssl rand -hex 32)"
printf 'user default off\nuser query-cache on >%s ~query:v2:tls:* -@all +hello +ping +client|setinfo +get +set +del +scan\nuser test-admin on >%s ~* +@all\n' \
  "$SIMPLEMATCH_REDIS_TEST_PASSWORD" "$SIMPLEMATCH_REDIS_TEST_ADMIN_PASSWORD" >"$temporary_directory/users.acl"

identities=(trusted mismatch untrusted)
for index in 0 1 2; do
  identity="${identities[$index]}"
  cat >"$temporary_directory/$identity.conf" <<EOF
bind 127.0.0.1
port 0
tls-port ${redis_ports[$index]}
tls-cert-file $temporary_directory/$identity.crt
tls-key-file $temporary_directory/$identity.key
tls-ca-cert-file $temporary_directory/ca.crt
tls-auth-clients no
aclfile $temporary_directory/users.acl
save ""
appendonly no
daemonize no
EOF
  "$redis_server" "$temporary_directory/$identity.conf" >"$temporary_directory/$identity.log" 2>&1 &
  redis_pids+=("$!")
done
python3 - "${redis_ports[@]}" <<'PY'
import socket, sys, time
for port in map(int, sys.argv[1:]):
    deadline = time.monotonic() + 10
    while True:
        try:
            with socket.create_connection(('127.0.0.1', port), timeout=.2):
                break
        except OSError:
            if time.monotonic() >= deadline:
                raise SystemExit('Disposable TLS Redis fixture did not start')
            time.sleep(.1)
PY

export SIMPLEMATCH_REDIS_TEST_CA="$temporary_directory/ca.crt"
export SIMPLEMATCH_REDIS_TEST_PORT="${redis_ports[0]}"
export SIMPLEMATCH_REDIS_TEST_MISMATCH_PORT="${redis_ports[1]}"
export SIMPLEMATCH_REDIS_TEST_UNTRUSTED_PORT="${redis_ports[2]}"
cd "$repo_root"
./gradlew :services:query-service:redisSecurityTest
