#!/usr/bin/env bash
set -Eeuo pipefail
IFS=$'\n\t'

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/lib/connect-worker-loss-scenario.sh
source "$script_dir/lib/connect-worker-loss-scenario.sh"
# shellcheck source=scripts/lib/connect-worker-loss-cli.sh
source "$script_dir/lib/connect-worker-loss-cli.sh"

connect_worker_loss_parse_args "$@" || exit $?
connect_worker_loss_request_is_ready || exit 2
connect_worker_loss_run
