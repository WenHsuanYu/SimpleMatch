#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd -- "$script_dir/../../../.." && pwd)"
# shellcheck source=scripts/lib/local-certification-provenance.sh
source "$repo_root/scripts/lib/local-certification-provenance.sh"

fail() {
  printf 'Verifier helper contract: %s\n' "$*" >&2
  exit 1
}

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
production_evidence="$tmp/production-like"
mkdir -p "$production_evidence"
export SIMPLEMATCH_PRODUCTION_LIKE_EVIDENCE_DIR="$production_evidence"

namespace=certification-contract
cat >"$production_evidence/run-context" <<EOF_CONTEXT
namespace=$namespace
cluster=simplematch-live
trading_day=2026-08-26
image_tag=contract
image_transport=kind-load
source_signature=unused-by-this-contract
EOF_CONTEXT

simplematch_record_certification_provenance \
  "$repo_root" "$production_evidence" "$namespace" \
  kind-load contract "$tmp/unused.lock" ||
  fail 'kind-load provenance should resolve the canonical verifier image'

expected_image='simplematch/risk-matching-e2e-verifier:contract'
actual_image="$(simplematch_certification_verifier_image "$repo_root" "$namespace")" ||
  fail 'matching source and namespace provenance should be accepted'
[[ "$actual_image" == "$expected_image" ]] ||
  fail "unexpected verifier image reference: $actual_image"

for manifest in \
  "$repo_root/deploy/k8s/verification/critical-consumer-kafka-observer-pod.yaml" \
  "$repo_root/deploy/k8s/verification/matching-event-observer-pod.yaml"; do
  rendered="$tmp/$(basename "$manifest")"
  simplematch_render_verifier_helper_manifest \
    "$manifest" "$actual_image" "$rendered" ||
    fail "could not render verifier image into $(basename "$manifest")"
  [[ "$(grep -Fc "image: $expected_image" "$rendered")" == 1 ]] ||
    fail 'rendered helper must contain the retained verifier image exactly once'
  if grep -Fq 'image: simplematch/risk-matching-e2e-verifier:local' "$rendered"; then
    fail 'rendered helper must not retain the tracked local placeholder image'
  fi
done

printf '%s\n' deadbeef >"$production_evidence/source-revision"
if simplematch_certification_verifier_image "$repo_root" "$namespace" >/dev/null 2>&1; then
  fail 'a different source revision must reject retained production-like evidence'
fi

git -C "$repo_root" rev-parse HEAD >"$production_evidence/source-revision"
if simplematch_certification_verifier_image "$repo_root" other-namespace >/dev/null 2>&1; then
  fail 'production-like evidence from another namespace must be rejected'
fi

rm -f "$production_evidence/verifier-image-reference"
if simplematch_certification_verifier_image "$repo_root" "$namespace" >/dev/null 2>&1; then
  fail 'missing verifier image provenance must reject the retained run'
fi

printf '%s\n' 'Verifier helper provenance and rendering contracts are valid.'
