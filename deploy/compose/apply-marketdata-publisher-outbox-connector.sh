#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec "${SCRIPT_DIR}/apply-outbox-connector.sh" \
  "${1:-${SCRIPT_DIR}/marketdata-publisher-outbox-connector.json}"
