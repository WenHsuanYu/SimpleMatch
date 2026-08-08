#!/usr/bin/env bash
set -euo pipefail

CONNECT_URL="${CONNECT_URL:-http://localhost:8083}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec "${SCRIPT_DIR}/apply-outbox-connector.sh" \
  "${1:-${SCRIPT_DIR}/risk-service-outbox-connector.json}"
