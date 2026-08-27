#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd -- "$script_dir/../../../.." && pwd)"
production_runner="$repo_root/scripts/run-local-production-like-certification.sh"
failure_support="$repo_root/scripts/end-to-end/critical-consumers/lib/failure-support.sh"
kafka_observation_interface="$repo_root/scripts/end-to-end/critical-consumers/lib/kafka-observation-interface.sh"
# shellcheck source=scripts/lib/local-certification-provenance.sh
source "$repo_root/scripts/lib/local-certification-provenance.sh"

fail() {
  printf 'Verifier helper contract: %s\n' "$*" >&2
  exit 1
}

grep -Fq 'local-certification-provenance.sh' "$production_runner" ||
  fail 'production-like runner must load the shared provenance module'
grep -Fq 'simplematch_record_certification_provenance' "$production_runner" ||
  fail 'completed production-like runs must record dependent-test provenance'
grep -Fq 'local-certification-provenance.sh' "$failure_support" ||
  fail 'failure certification must load the same provenance module'

tmp="$(mktemp -d)"
untracked_probe="$repo_root/scripts/.certification-provenance-untracked-$$"
trap 'rm -rf "$tmp"; rm -f "$untracked_probe"' EXIT
production_evidence="$tmp/production-like"
mkdir -p "$production_evidence"

namespace=certification-contract
cat >"$production_evidence/run-context" <<EOF_CONTEXT
namespace=$namespace
cluster=simplematch-live
trading_day=2026-08-26
image_tag=contract
image_transport=kind-load
source_signature=unused-by-this-contract
EOF_CONTEXT

expected_kind_identity="sha256:$(printf 'b%.0s' {1..64})"
docker() {
  [[ "$1" == image && "$2" == inspect && "$3" == --format ]] || return 99
  printf '%s\n' "$expected_kind_identity"
}

simplematch_record_certification_provenance \
  "$repo_root" "$production_evidence" "$namespace" \
  kind-load contract "$tmp/unused.lock" ||
  fail 'kind-load provenance should resolve the canonical verifier image and identity'

expected_image='simplematch/risk-matching-e2e-verifier:contract'
[[ "$(cat "$production_evidence/verifier-image-identity")" == "$expected_kind_identity" ]] ||
  fail 'kind-load provenance must retain the immutable verifier image identity'

partial_evidence="$tmp/partial-provenance"
mkdir -p "$partial_evidence/source-revision"
if simplematch_record_certification_provenance \
    "$repo_root" "$partial_evidence" "$namespace" \
    kind-load contract "$tmp/unused.lock" >/dev/null 2>&1; then
  fail 'a failed required provenance write must fail the whole operation'
fi

# The dependent runner must be able to select a custom retained evidence
# directory directly. No similarly named environment variable is required.
unset SIMPLEMATCH_PRODUCTION_LIKE_EVIDENCE_DIR || true
actual_image="$(
  simplematch_certification_verifier_image \
    "$repo_root" "$namespace" "$production_evidence"
)" || fail 'explicit retained evidence with matching source and namespace should be accepted'
[[ "$actual_image" == "$expected_image" ]] ||
  fail "unexpected verifier image reference: $actual_image"

# An untracked repository file can participate in a local build even though HEAD
# is unchanged. Provenance must therefore reject it rather than certifying only
# the commit identity.
printf '%s\n' '# untracked certification source probe' >"$untracked_probe"
if simplematch_certification_verifier_image \
    "$repo_root" "$namespace" "$production_evidence" >/dev/null 2>&1; then
  fail 'untracked repository source must reject retained production-like evidence'
fi
rm -f "$untracked_probe"

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

# Kafka helper preparation is independent of the optional Matching Event
# observer. This catches the hidden-global failure seen by Gateway close
# certification before any Kubernetes deployment is attempted.
# shellcheck source=scripts/end-to-end/critical-consumers/lib/kafka-observation-interface.sh
source "$kafka_observation_interface"
evidence_dir="$tmp/dependent"
mkdir -p "$evidence_dir/baseline"
kafka_observer_manifest="$repo_root/deploy/k8s/verification/critical-consumer-kafka-observer-pod.yaml"
unset observer_manifest || true
prepare_kafka_observer_manifest "$production_evidence" ||
  fail 'Kafka observer preparation must not require an event-observer manifest'
[[ -f "$evidence_dir/baseline/verifier-helper-manifests/kafka-observer.yaml" ]] ||
  fail 'Kafka observer preparation did not render its helper manifest'

(
  simplematch_certification_image_transport() {
    printf '%s\n' kind-load
  }
  simplematch_certification_verifier_image_identity() {
    printf '%s\n' "$expected_kind_identity"
  }
  simplematch_certification_verifier_image() {
    printf '%s\n' "$expected_image"
  }
  kns() {
    [[ "$1" == get && "$2" == pod && "$3" == critical-consumer-kafka-observer ]] || return 91
    printf '%s\n' simplematch-live-worker
  }
  docker() {
    [[ "$1" == exec && "$2" == simplematch-live-worker \
       && "$3" == crictl && "$4" == inspecti && "$5" == "$expected_image" ]] || return 92
    jq -n --arg id "$expected_kind_identity" '{status:{id:$id}}'
  }
  verify_kind_loaded_verifier_image_identity \
    critical-consumer-kafka-observer "$production_evidence"
) || fail 'kind-load helper must match the CRI image identity on its actual node'

wrong_identity="sha256:$(printf 'c%.0s' {1..64})"
(
  simplematch_certification_image_transport() {
    printf '%s\n' kind-load
  }
  simplematch_certification_verifier_image_identity() {
    printf '%s\n' "$expected_kind_identity"
  }
  simplematch_certification_verifier_image() {
    printf '%s\n' "$expected_image"
  }
  kns() {
    printf '%s\n' simplematch-live-worker
  }
  docker() {
    jq -n --arg id "$wrong_identity" '{status:{id:$id}}'
  }
  if verify_kind_loaded_verifier_image_identity \
      critical-consumer-kafka-observer "$production_evidence"; then
    exit 93
  fi
) || {
  [[ "$?" == 93 ]] && fail 'a different node CRI image identity must be rejected'
}

observer_manifest="$repo_root/deploy/k8s/verification/matching-event-observer-pod.yaml"
prepare_matching_event_observer_manifest "$production_evidence" ||
  fail 'Matching Event observer preparation should remain independently available'
[[ -f "$evidence_dir/baseline/verifier-helper-manifests/matching-event-observer.yaml" ]] ||
  fail 'Matching Event observer preparation did not render its helper manifest'

registry_digest="localhost:5001/simplematch/risk-matching-e2e-verifier@sha256:$(printf 'a%.0s' {1..64})"
cat >"$tmp/registry.lock" <<EOF_LOCK
risk-matching-e2e-verifier|simplematch/risk-matching-e2e-verifier:contract|localhost:5001/simplematch/risk-matching-e2e-verifier:contract|$registry_digest
EOF_LOCK
sed -i 's/image_transport=kind-load/image_transport=registry/' "$production_evidence/run-context"
simplematch_record_certification_provenance \
  "$repo_root" "$production_evidence" "$namespace" \
  registry contract "$tmp/registry.lock" ||
  fail 'registry provenance should preserve the digest-pinned verifier image'
actual_image="$(
  simplematch_certification_verifier_image \
    "$repo_root" "$namespace" "$production_evidence"
)" || fail 'digest-pinned registry provenance should be accepted'
[[ "$actual_image" == "$registry_digest" ]] ||
  fail "unexpected registry verifier image reference: $actual_image"
[[ "$(cat "$production_evidence/verifier-image-identity")" == "${registry_digest##*@}" ]] ||
  fail 'registry verifier identity must equal the digest-pinned reference'

printf '%s\n' deadbeef >"$production_evidence/source-revision"
if simplematch_certification_verifier_image \
    "$repo_root" "$namespace" "$production_evidence" >/dev/null 2>&1; then
  fail 'a different source revision must reject retained production-like evidence'
fi

git -C "$repo_root" rev-parse HEAD >"$production_evidence/source-revision"
if simplematch_certification_verifier_image \
    "$repo_root" other-namespace "$production_evidence" >/dev/null 2>&1; then
  fail 'production-like evidence from another namespace must be rejected'
fi

rm -f "$production_evidence/verifier-image-reference"
if simplematch_certification_verifier_image \
    "$repo_root" "$namespace" "$production_evidence" >/dev/null 2>&1; then
  fail 'missing verifier image provenance must reject the retained run'
fi

printf '%s\n' 'Verifier helper provenance and rendering contracts are valid.'
