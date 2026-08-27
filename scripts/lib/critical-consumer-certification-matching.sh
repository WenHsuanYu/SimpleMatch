#!/usr/bin/env bash

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/end-to-end/critical-consumers/lib/matching-status.sh
source "$script_dir/../end-to-end/critical-consumers/lib/matching-status.sh"
