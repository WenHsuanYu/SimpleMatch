#!/usr/bin/env bash
# Focused QS1-06 proof. This never starts the full trading-system certification.
set -Eeuo pipefail
umask 077
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
context=kind-simplematch-live
docker_context=desktop-linux
evidence_dir=""
image_tag="qs193-$(git -C "$repo_root" rev-parse --short HEAD)"
rejection_image=simplematch/query-service:local
while (($#)); do
  case "$1" in
    --help)
      printf '%s\n' 'Usage: run-query-security-lifecycle.sh --directory NEW_PATH [--context CONTEXT] [--docker-context CONTEXT] [--image-tag TAG] [--rejection-image REFERENCE]'
      printf '%s\n' 'Synthetic Local Lab only. Creates and destroys two owned namespaces; no resume or production guarantee.'
      exit 0 ;;
    --directory|--context|--docker-context|--image-tag|--rejection-image)
      (($# >= 2)) || exit 2
      case "$1" in
        --directory) evidence_dir="$2" ;;
        --context) context="$2" ;;
        --docker-context) docker_context="$2" ;;
        --image-tag) image_tag="$2" ;;
        --rejection-image) rejection_image="$2" ;;
      esac
      shift 2 ;;
    *) printf 'Unsupported option: %s\n' "$1" >&2; exit 2 ;;
  esac
done
[[ -n "$evidence_dir" && "$image_tag" =~ ^[a-zA-Z0-9_][a-zA-Z0-9_.-]{0,127}$ ]] || exit 2
evidence_dir="$(realpath -m "$evidence_dir")"
mkdir -p "$(dirname "$evidence_dir")"
mkdir -m 700 "$evidence_dir"
cd "$repo_root"
source "$repo_root/scripts/lib/local-certification-provenance.sh"
source_signature="$(simplematch_certification_source_signature "$repo_root")"
export DOCKER_HOST
DOCKER_HOST="$(docker context inspect "$docker_context" --format '{{.Endpoints.docker.Host}}')"
unset DOCKER_CONTEXT
export SIMPLEMATCH_BOOT_RUN_IMAGE="${SIMPLEMATCH_BOOT_RUN_IMAGE:-paketobuildpacks/ubuntu-noble-run-tiny:0.0.122}"
export SIMPLEMATCH_BOOT_PULL_POLICY=IF_NOT_PRESENT
query_security_lifecycle_only=true
run_id="query-security-$(date -u +%Y%m%dT%H%M%S)-$$"
source "$repo_root/scripts/lib/local-certification-phase-graph.sh"
source "$repo_root/scripts/lib/local-certification-evidence.sh"
phase_id=""
failed() {
  local status="$?"
  trap - ERR
  if [[ -n "$phase_id" ]]; then
    certification_write_phase_result "$phase_id" FAIL "$status" || true
  fi
  printf 'Query lifecycle FAIL; retained evidence: %s\n' "$evidence_dir" >&2
  exit "$status"
}
trap failed ERR
printf 'run_id=%s\nsource_signature=%s\n' "$run_id" "$source_signature" >"$evidence_dir/run-context"
git rev-parse HEAD >"$evidence_dir/source-revision"
required_phases="$(certification_required_phase_ids)"
mapfile -t phases <<<"$required_phases"
for phase_id in "${phases[@]}"; do
  printf 'EXECUTE %s\n' "$phase_id"
  certification_write_phase_result "$phase_id" RUNNING
  case "$phase_id" in
    source-preflight)
      python3 scripts/end-to-end/query-service/security-lifecycle.py preflight --context "$context" \
        --docker-context "$docker_context" --directory "$evidence_dir" --rejection-image "$rejection_image"
      git status --porcelain >"$evidence_dir/source-worktree.txt"
      git rev-parse HEAD >"$evidence_dir/source-head.txt"
      ;;
    local-image-inventory)
      bash scripts/build-local-images.sh --tag "$image_tag" --list >"$evidence_dir/image-inventory.txt"
      ;;
    local-image-build/query-service)
      ./gradlew -q :services:query-service:testClasses >"$evidence_dir/fixture-compile.log" 2>&1
      bash scripts/build-local-images.sh --service query-service --tag "$image_tag-boot" >"$evidence_dir/boot-image.log" 2>&1
      mkdir "$evidence_dir/empty-context" "$evidence_dir/buildx"
      BUILDX_CONFIG="$evidence_dir/buildx" docker build \
        --build-arg "SOURCE_IMAGE=simplematch/query-service:$image_tag-boot" \
        --file deploy/docker/Dockerfile.kind-normalized --tag "simplematch/query-service:$image_tag" \
        "$evidence_dir/empty-context" >"$evidence_dir/normalized-image.log" 2>&1
      ;;
    query-security-lifecycle)
      # The test publisher is mounted separately from the boot image.
      ./gradlew -q :services:query-service:testClasses >"$evidence_dir/fixture-compile.log" 2>&1
      sha256sum services/query-service/build/classes/java/test/com/simplematch/queryservice/security/QueryLifecycleFixtureMain.class \
        >"$evidence_dir/fixture-class.sha256"
      mkdir "$evidence_dir/runtime"
      python3 scripts/end-to-end/query-service/security-lifecycle.py run --context "$context" \
        --docker-context "$docker_context" --directory "$evidence_dir/runtime" \
        --image "simplematch/query-service:$image_tag" --rejection-image "$rejection_image"
      jq -e '.status == "PASS"' "$evidence_dir/runtime/verdict.json" >/dev/null
      ;;
  esac
  certification_write_phase_result "$phase_id" PASS 0
done
certification_write_completion_manifest "$evidence_dir" "$required_phases"
printf 'Query lifecycle PASS (Local Lab only): %s\n' "$evidence_dir"
