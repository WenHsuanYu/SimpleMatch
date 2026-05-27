#!/usr/bin/env bash
set -euo pipefail

CONNECT_URL="${CONNECT_URL:-http://localhost:8083}"
CONNECTOR_NAME="risk-service-outbox"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CONNECTOR_PATH="${1:-${SCRIPT_DIR}/risk-service-outbox-connector.json}"
POSTGRES_HOST="${POSTGRES_HOST:-host.docker.internal}"
POSTGRES_PORT="${POSTGRES_PORT:-5432}"
POSTGRES_USER="${POSTGRES_USER:-simplematch}"
POSTGRES_PASSWORD="${POSTGRES_PASSWORD:-simplematch}"
POSTGRES_DB="${POSTGRES_DB:-simplematch}"

if [[ ! -f "${CONNECTOR_PATH}" ]]; then
  echo "Connector config not found: ${CONNECTOR_PATH}" >&2
  exit 1
fi

if ! curl -fsS "${CONNECT_URL}/connectors" >/dev/null; then
  echo "Kafka Connect is not reachable at ${CONNECT_URL}" >&2
  exit 1
fi

response_file="$(mktemp)"
payload_file="$(mktemp)"
trap 'rm -f "${response_file}" "${payload_file}"' EXIT

sed \
  -e "s|\${POSTGRES_HOST}|${POSTGRES_HOST}|g" \
  -e "s|\${POSTGRES_PORT}|${POSTGRES_PORT}|g" \
  -e "s|\${POSTGRES_USER}|${POSTGRES_USER}|g" \
  -e "s|\${POSTGRES_PASSWORD}|${POSTGRES_PASSWORD}|g" \
  -e "s|\${POSTGRES_DB}|${POSTGRES_DB}|g" \
  "${CONNECTOR_PATH}" > "${payload_file}"

status_code="$(curl -sS -o "${response_file}" -w '%{http_code}' \
  -X POST \
  -H 'Content-Type: application/json' \
  --data @"${payload_file}" \
  "${CONNECT_URL}/connectors")"

case "${status_code}" in
  200|201)
    echo "Connector ${CONNECTOR_NAME} created via ${CONNECT_URL}."
    ;;
  409)
    if ! command -v jq >/dev/null 2>&1; then
      echo "Connector ${CONNECTOR_NAME} already exists. Install jq to enable in-place updates." >&2
      cat "${response_file}" >&2
      exit 1
    fi

    jq -c '.config' "${payload_file}" | curl -fsS \
      -X PUT \
      -H 'Content-Type: application/json' \
      --data @- \
      "${CONNECT_URL}/connectors/${CONNECTOR_NAME}/config" >/dev/null
    echo "Connector ${CONNECTOR_NAME} updated via ${CONNECT_URL}."
    ;;
  *)
    echo "Failed to apply connector ${CONNECTOR_NAME} via ${CONNECT_URL} (HTTP ${status_code})." >&2
    cat "${response_file}" >&2
    exit 1
    ;;
esac