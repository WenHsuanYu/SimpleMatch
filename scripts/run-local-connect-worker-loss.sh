#!/usr/bin/env bash
set -Eeuo pipefail
IFS=$'\n\t'

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/lib/connect-worker-loss-scenario.sh
source "$script_dir/lib/connect-worker-loss-scenario.sh"

connect_worker_loss_main "$@"
