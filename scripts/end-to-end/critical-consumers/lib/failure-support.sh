#!/usr/bin/env bash

# Compatibility aggregator for the failure-certification support modules.
module_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/end-to-end/critical-consumers/lib/cluster-data.sh
source "$module_dir/cluster-data.sh"
# shellcheck source=scripts/end-to-end/critical-consumers/lib/test-interfaces.sh
source "$module_dir/test-interfaces.sh"
# shellcheck source=scripts/lib/local-certification-provenance.sh
source "$module_dir/../../../lib/local-certification-provenance.sh"
# shellcheck source=scripts/end-to-end/critical-consumers/lib/kafka-observation-interface.sh
source "$module_dir/kafka-observation-interface.sh"
# shellcheck source=scripts/end-to-end/critical-consumers/lib/failure-recovery.sh
source "$module_dir/failure-recovery.sh"
