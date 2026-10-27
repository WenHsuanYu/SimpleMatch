#!/usr/bin/env bash
set -euo pipefail
umask 077

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
command -v openssl >/dev/null || {
  echo "Query Redis security test requires openssl" >&2
  exit 1
}

temporary_directory="$(mktemp -d "${TMPDIR:-/tmp}/simplematch-query-redis.XXXXXX")"
trap 'rm -rf -- "$temporary_directory"' EXIT

openssl req -x509 -newkey rsa:2048 -nodes -sha256 -days 1 -subj /CN=Query-Redis-Test-CA \
  -keyout "$temporary_directory/ca.key" -out "$temporary_directory/ca.crt" >/dev/null 2>&1
for identity in trusted mismatch; do
  hostname=localhost
  [[ "$identity" != mismatch ]] || hostname=wrong.example.invalid
  openssl req -new -newkey rsa:2048 -nodes -subj "/CN=$hostname" \
    -keyout "$temporary_directory/$identity.key" -out "$temporary_directory/$identity.csr" >/dev/null 2>&1
  subject_alt_name="DNS:$hostname"
  if [[ "$identity" == trusted ]]; then
    subject_alt_name+=",IP:127.0.0.1"
  fi
  printf 'subjectAltName=%s\nextendedKeyUsage=serverAuth\n' "$subject_alt_name" >"$temporary_directory/$identity.ext"
  openssl x509 -req -sha256 -days 1 -in "$temporary_directory/$identity.csr" \
    -CA "$temporary_directory/ca.crt" -CAkey "$temporary_directory/ca.key" -CAcreateserial \
    -extfile "$temporary_directory/$identity.ext" -out "$temporary_directory/$identity.crt" >/dev/null 2>&1
done
openssl req -new -newkey rsa:2048 -nodes -subj /CN=localhost \
  -keyout "$temporary_directory/untrusted.key" -out "$temporary_directory/untrusted.csr" >/dev/null 2>&1
openssl x509 -req -sha256 -days 1 -in "$temporary_directory/untrusted.csr" \
  -signkey "$temporary_directory/untrusted.key" -extfile "$temporary_directory/trusted.ext" \
  -out "$temporary_directory/untrusted.crt" >/dev/null 2>&1

SIMPLEMATCH_REDIS_TEST_PASSWORD="$(openssl rand -hex 32)"
SIMPLEMATCH_REDIS_TEST_ADMIN_PASSWORD="$(openssl rand -hex 32)"
export SIMPLEMATCH_REDIS_TEST_PASSWORD SIMPLEMATCH_REDIS_TEST_ADMIN_PASSWORD
printf 'user default off\nuser query-cache on >%s ~query:v2:tls:* -@all +hello +ping +client|setinfo +get +set +del +scan\nuser test-admin on >%s ~* +@all\n' \
  "$SIMPLEMATCH_REDIS_TEST_PASSWORD" "$SIMPLEMATCH_REDIS_TEST_ADMIN_PASSWORD" >"$temporary_directory/users.acl"

export SIMPLEMATCH_REDIS_TEST_DIRECTORY="$temporary_directory"
export SIMPLEMATCH_REDIS_TEST_CA="$temporary_directory/ca.crt"
cd "$repo_root"
./gradlew :services:query-service:redisSecurityTest "$@"
