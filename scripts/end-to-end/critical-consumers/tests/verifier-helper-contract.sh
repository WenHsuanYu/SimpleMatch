#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd -- "$script_dir/../../../.." && pwd)"
production_runner="$repo_root/scripts/run-local-production-like-certification.sh"
phase_graph="$repo_root/scripts/lib/local-certification-phase-graph.sh"
production_run="$repo_root/scripts/lib/local-certification-run.sh"
failure_support="$repo_root/scripts/end-to-end/critical-consumers/lib/failure-support.sh"
# shellcheck source=scripts/lib/local-certification-provenance.sh
source "$repo_root/scripts/lib/local-certification-provenance.sh"
# shellcheck source=scripts/lib/local-certification-phase-graph.sh
source "$phase_graph"

fail() {
  printf 'Verifier helper contract: %s\n' "$*" >&2
  exit 1
}

grep -Fq 'local-certification-provenance.sh' "$production_runner" ||
  fail 'production-like runner must load the shared provenance module'
grep -Fq 'simplematch_record_certification_provenance' "$production_run" ||
  fail 'production-like phase dispatcher must provide retained provenance'
grep -Fq 'local-certification-provenance.sh' "$failure_support" ||
  fail 'failure certification must load the same provenance module'

skip_build=false
skip_compose=false
skip_kubernetes=false
matching_fleet_only=false
image_transport=registry
required_phases="$(certification_required_phase_ids)" ||
  fail 'full production-like Phase DAG must resolve'
grep -Fxq retained-run-provenance <<<"$required_phases" ||
  fail 'completed full production-like runs must require retained provenance'
provenance_dependencies="$(
  certification_phase_dependencies retained-run-provenance
)" || fail 'retained provenance dependencies must resolve'
grep -Fxq kubernetes-fleet <<<"$provenance_dependencies" ||
  fail 'retained provenance must depend on the completed Kubernetes fleet proof'

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

registry_digest="localhost:5001/simplematch/risk-matching-e2e-verifier@sha256:$(printf 'a%.0s' {1..64})"
cat >"$tmp/registry.lock" <<EOF_LOCK
risk-matching-e2e-verifier|simplematch/risk-matching-e2e-verifier:contract|localhost:5001/simplematch/risk-matching-e2e-verifier:contract|$registry_digest
EOF_LOCK
simplematch_record_certification_provenance \
  "$repo_root" "$production_evidence" "$namespace" \
  registry contract "$tmp/registry.lock" ||
  fail 'registry provenance should preserve the digest-pinned verifier image'
actual_image="$(simplematch_certification_verifier_image "$repo_root" "$namespace")" ||
  fail 'digest-pinned registry provenance should be accepted'
[[ "$actual_image" == "$registry_digest" ]] ||
  fail "unexpected registry verifier image reference: $actual_image"

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
