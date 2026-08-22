#!/usr/bin/env bash
set -Eeuo pipefail
IFS=$'\n\t'
trap 'printf "Local lifecycle safety contract failed at line %s\n" "$LINENO" >&2' ERR

archive_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
script_dir="$(cd -- "$archive_dir/.." && pwd)"
# shellcheck source=scripts/lib/local-common.sh
source "$script_dir/lib/local-common.sh"
# shellcheck source=scripts/lib/local-kind.sh
source "$script_dir/lib/local-kind.sh"

registry_lib="$script_dir/lib/local-registry.sh"
hard_reset="$script_dir/hard-reset-local.sh"

# Observation failure is not evidence of absence. Routine cleanup must stop
# rather than treating an unavailable API server as an empty resource set.
if (
  kubectl() { return 42; }
  simplematch_kind_disposable_namespaces kind-simplematch-live
) >/dev/null 2>&1; then
  printf '%s\n' 'failed namespace observation was treated as an empty disposable namespace set' >&2
  exit 1
fi

if (
  kubectl() { return 42; }
  simplematch_kind_wait_claim_pvs_gone kind-simplematch-live simplematch-contract 1
) >/dev/null 2>&1; then
  printf '%s\n' 'failed PV observation was treated as completed PV cleanup' >&2
  exit 1
fi

# The repository registry is deliberately local-only. A configurable port is
# useful for a local lab; a configurable remote host would make a push capable of
# crossing the ownership boundary that this workflow is meant to enforce.
if (
  SIMPLEMATCH_LOCAL_REGISTRY_HOST=registry.example.com
  # shellcheck source=scripts/lib/local-registry.sh
  source "$registry_lib"
  simplematch_registry_endpoint >/dev/null
) 2>/dev/null; then
  printf '%s\n' 'remote local-registry host override was unexpectedly accepted' >&2
  exit 1
fi

for invalid_port in 0 65536 not-a-port; do
  if (
    SIMPLEMATCH_LOCAL_REGISTRY_HOST=localhost
    SIMPLEMATCH_LOCAL_REGISTRY_PORT="$invalid_port"
    # shellcheck source=scripts/lib/local-registry.sh
    source "$registry_lib"
    simplematch_registry_endpoint >/dev/null
  ) 2>/dev/null; then
    printf 'invalid local-registry port was unexpectedly accepted: %s\n' "$invalid_port" >&2
    exit 1
  fi
done

# Step 7 destructive ownership is centralized behind the two managers. The hard
# reset may read shared registry constants, but it must not bypass the registry
# manager to perform deletion.
grep -Fq 'registry_manager="$script_dir/manage-local-registry.sh"' "$hard_reset"
grep -Fq 'simplematch_run bash "$registry_manager" "${args[@]}"' "$hard_reset"
if grep -Fq 'simplematch_registry_delete' "$hard_reset"; then
  printf '%s\n' 'hard reset bypasses the registry manager deletion boundary' >&2
  exit 1
fi

grep -Fq 'simplematch_run bash "$script_dir/manage-simplematch-live.sh"' "$hard_reset"

printf '%s\n' 'Local lifecycle fail-closed and destructive-ownership safety contract passed.'
