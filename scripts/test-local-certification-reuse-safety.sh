#!/usr/bin/env bash
set -Eeuo pipefail
IFS=$'\n\t'

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd -- "$script_dir/.." && pwd)"

# shellcheck source=scripts/lib/local-certification-phase-graph.sh
source "$script_dir/lib/local-certification-phase-graph.sh"
# shellcheck source=scripts/lib/local-certification-fingerprint.sh
source "$script_dir/lib/local-certification-fingerprint.sh"
# shellcheck source=scripts/lib/local-certification-evidence.sh
source "$script_dir/lib/local-certification-evidence.sh"
# shellcheck source=scripts/lib/local-certification-planner.sh
source "$script_dir/lib/local-certification-planner.sh"
# shellcheck source=scripts/lib/local-certification-images.sh
source "$script_dir/lib/local-certification-images.sh"
# shellcheck source=scripts/lib/local-certification-provenance.sh
source "$script_dir/lib/local-certification-provenance.sh"

fail() {
  printf 'certification reuse safety contract failed: %s\n' "$*" >&2
  exit 1
}

fixture_root="$(mktemp -d)"
trap 'rm -rf "$fixture_root"' EXIT

# Producer failures are not empty selections. This protects the PhaseGraph from
# process-substitution behavior that otherwise hides the producer exit status.
if (
  simplematch_local_image_inventory_entries() {
    return 1
  }
  certification_selected_image_services >/dev/null 2>&1
); then
  fail 'image inventory failure was treated as an empty image profile'
fi

# Fingerprint input discovery must preserve the same fail-closed rule. A failed
# producer cannot become a valid fingerprint over an empty input set.
if (
  _certification_list_git_inputs() {
    return 1
  }
  certification_fingerprint_paths services/account-service >/dev/null 2>&1
); then
  fail 'failed Git input discovery produced an empty fingerprint input set'
fi

if (
  awk() {
    return 1
  }
  _certification_dockerfile_base_identities \
    deploy/docker/Dockerfile.kind-normalized >/dev/null 2>&1
); then
  fail 'failed Dockerfile base discovery was treated as no base images'
fi

if (
  certification_selected_image_services() {
    return 1
  }
  registry_fragment_directory="$fixture_root/fingerprint-fragments"
  _certification_registry_lock_fingerprint 1 >/dev/null 2>&1
); then
  fail 'failed image selection produced a registry-lock fingerprint'
fi

# Image helpers are called from conditional contexts by the top-level runner.
# They must therefore propagate every phase failure explicitly instead of
# relying on Bash errexit behavior.
(
  calls_file="$fixture_root/build-calls"
  certification_selected_image_services() {
    printf '%s\n' first second
  }
  run_logged() {
    printf '%s\n' "$1" >>"$calls_file"
    return 1
  }
  image_tag=local
  if certification_build_local_images; then
    exit 91
  fi
  [[ "$(wc -l <"$calls_file")" -eq 1 ]]
) || fail 'image build helper continued after a failed phase'

(
  calls_file="$fixture_root/registry-calls"
  certification_selected_image_services() {
    printf '%s\n' first second
  }
  run_logged() {
    printf '%s\n' "$1" >>"$calls_file"
    return 1
  }
  dry_run=true
  evidence_dir="$fixture_root/registry-run"
  kind_cluster=simplematch-live
  if certification_publish_registry_images; then
    exit 91
  fi
  [[ "$(wc -l <"$calls_file")" -eq 1 ]]
  grep -Fxq registry-connectivity "$calls_file"
) || fail 'registry preparation continued after connectivity failure'

# The planner must propagate graph producer failures instead of planning a phase
# with an accidentally empty dependency set.
dry_run=false
resume=false
image_transport=registry
matching_fleet_only=false
skip_build=false
skip_compose=false
skip_kubernetes=false
certification_trading_day=2026-08-28
source_signature=reuse-safety
run_id=reuse-safety
evidence_dir="$fixture_root/planner"
export SIMPLEMATCH_CERTIFICATION_CACHE_DIR="$fixture_root/cache"
certification_plan_initialize "$evidence_dir" || \
  fail 'planner fixture could not initialize'
(
  certification_phase_dependencies() {
    return 1
  }
  if certification_plan_phase source-preflight >/dev/null 2>&1; then
    exit 91
  fi
) || fail 'planner accepted a failed dependency producer'

(
  certification_required_phase_ids() {
    return 1
  }
  if certification_plan_finalize >/dev/null 2>&1; then
    exit 91
  fi
) || fail 'plan finalization accepted a failed required-phase producer'

# A cached Docker image is reusable only at the source-image location requested
# by the current run. Matching the immutable ID at a different tag is not
# sufficient because later registry publication consumes the requested tag.
image_input="sha256:$(printf 'image-input' | sha256sum | awk '{print $1}')"
image_identity="sha256:eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"
image_result="$fixture_root/image-result.json"
jq -n \
  --arg input "$image_input" \
  --arg identity "$image_identity" '{
    schemaVersion: 1,
    phaseId: "local-image-build/quickfix-gateway",
    definitionVersion: 1,
    decision: "EXECUTED",
    status: "PASS",
    inputFingerprint: $input,
    evidenceDigest: null,
    reason: "test",
    execution: {
      sourceRevision: "test",
      startedAtUtc: "2026-08-28T00:00:00Z",
      completedAtUtc: "2026-08-28T00:00:01Z",
      durationMillis: 1000
    },
    outputs: [{
      kind: "docker-image",
      name: "quickfix-gateway",
      identity: $identity,
      location: "quickfix-gateway:local"
    }]
  }' >"$image_result"
image_evidence="$(certification_evidence_publish \
  local-image-build/quickfix-gateway "$image_input" "$image_result")" || \
  fail 'image reuse evidence could not be published'

docker() {
  if [[ "$1" == image && "$2" == inspect ]]; then
    printf '%s\n' "$image_identity"
    return 0
  fi
  return 1
}

image_tag=local
certification_image_phase_cached_outputs_valid \
  local-image-build/quickfix-gateway "$image_evidence" || \
  fail 'matching image tag and immutable identity were rejected'
image_tag=other
if certification_image_phase_cached_outputs_valid \
    local-image-build/quickfix-gateway "$image_evidence"; then
  fail 'cached image from a different requested tag was accepted'
fi

# Deployment-only changes are outside the effective build inputs of Spring
# application images. The fingerprint seam is exercised against the real
# repository while the remote buildpack identities are deterministic adapters.
(
  deployment_file="$repo_root/deploy/k8s/README.md"
  deployment_backup="$fixture_root/deployment-readme.backup"
  cp -- "$deployment_file" "$deployment_backup"
  trap 'cp -- "$deployment_backup" "$deployment_file"' EXIT

  _certification_spring_toolchain_identity() {
    printf '%s\n' \
      'builder=test-builder@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa' \
      'runImage=test-run@sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb' \
      'pullPolicy=DEFAULT'
  }

  _certification_spring_common_fingerprint=""
  _certification_spring_toolchain_fingerprint=""
  before="$(certification_image_input_fingerprint account-service)"
  printf '\n<!-- deployment-only fingerprint probe -->\n' >>"$deployment_file"
  _certification_spring_common_fingerprint=""
  _certification_spring_toolchain_fingerprint=""
  after="$(certification_image_input_fingerprint account-service)"
  [[ "$before" == "$after" ]]
) || fail 'deployment-only change invalidated an application image build'

# Cross-run cache is an optimization, not a correctness dependency. A retained
# run must materialize reused evidence locally so dependent certification still
# resolves its verifier identity after the reusable cache is removed.
(
  retained_cache="$fixture_root/retained-cache"
  cold_run="$fixture_root/retained-cold"
  retained_run="$fixture_root/retained-warm"
  namespace=simplematch-local-cert-retained-test
  verifier_reference="localhost:5001/risk-matching-e2e-verifier@sha256:ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"
  export SIMPLEMATCH_CERTIFICATION_CACHE_DIR="$retained_cache"
  zero_timing="$(certification_execution_timing_json \
    2026-08-28T00:00:00Z 2026-08-28T00:00:00Z 0)" || return 1

  certification_required_phase_ids() {
    printf '%s\n' source-preflight static-kubernetes-overlays
  }
  certification_phase_fingerprint() {
    case "$1" in
      source-preflight)
        printf '%s\n' \
          'sha256:1111111111111111111111111111111111111111111111111111111111111111'
        ;;
      static-kubernetes-overlays)
        printf '%s\n' \
          'sha256:2222222222222222222222222222222222222222222222222222222222222222'
        ;;
      *) return 1 ;;
    esac
  }

  record_fresh_phase() {
    local phase_id="$1"
    local plan decision input _evidence reason

    plan="$(certification_plan_phase "$phase_id")" || return 1
    IFS='|' read -r decision input _evidence reason <<<"$plan"
    [[ "$decision" == EXECUTE ]] || return 1
    certification_plan_record_execution \
      "$phase_id" "$input" "$reason" "$zero_timing"
  }

  evidence_dir="$cold_run"
  run_id=retained-cold
  certification_plan_initialize "$evidence_dir" || return 1
  record_fresh_phase source-preflight || return 1
  record_fresh_phase static-kubernetes-overlays || return 1
  certification_plan_finalize || return 1

  evidence_dir="$retained_run"
  run_id=retained-warm
  certification_plan_initialize "$evidence_dir" || return 1
  record_fresh_phase source-preflight || return 1
  warm_plan="$(certification_plan_phase static-kubernetes-overlays)" || return 1
  IFS='|' read -r warm_decision warm_input warm_evidence warm_reason \
    <<<"$warm_plan"
  [[ "$warm_decision" == REUSE ]] || return 1
  certification_plan_record_reuse \
    static-kubernetes-overlays "$warm_decision" "$warm_input" \
    "$warm_evidence" "$warm_reason" "$zero_timing" || return 1
  certification_plan_finalize || return 1

  current_revision="$(git -C "$repo_root" rev-parse HEAD)" || return 1
  printf 'namespace=%s\n' "$namespace" >"$retained_run/run-context"
  printf '%s\n' "$current_revision" >"$retained_run/source-revision"
  printf '%s\n' "$verifier_reference" \
    >"$retained_run/verifier-image-reference"
  printf '%s\n' "$namespace" >"$retained_run/retained-namespace"

  rm -rf -- "$retained_cache"
  [[ -f "$retained_run/evidence-manifest.json" ]] || return 1
  [[ -f "$retained_run/phases/static-kubernetes-overlays/source-evidence.json" ]] || \
    return 1
  jq -e '
    .schemaVersion == 1 and
    ([.phases[].phaseId] | index("static-kubernetes-overlays") != null)
  ' "$retained_run/evidence-manifest.json" >/dev/null || return 1
  jq -e '
    .phaseId == "static-kubernetes-overlays" and
    .decision == "REUSED" and
    .status == "PASS"
  ' "$retained_run/phases/static-kubernetes-overlays/result.json" \
    >/dev/null || return 1

  resolved_verifier="$(
    SIMPLEMATCH_PRODUCTION_LIKE_EVIDENCE_DIR="$retained_run" \
      simplematch_certification_verifier_image "$repo_root" "$namespace"
  )" || return 1
  [[ "$resolved_verifier" == "$verifier_reference" ]]
) || fail 'retained run depended on the reusable cache after materialization'

printf '%s\n' 'Local certification reuse safety contracts are valid.'