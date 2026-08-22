#!/usr/bin/env bash

set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd -- "$script_dir/.." && pwd)"
# shellcheck source=scripts/lib/local-image-inventory.sh
source "$script_dir/lib/local-image-inventory.sh"

image_tag="${SIMPLEMATCH_LOCAL_IMAGE_TAG:-local}"
dry_run=false
list_only=false
skip_spring=false
skip_native=false
skip_flyway=false
skip_verifier=false
selected_services=()

usage() {
  cat <<'EOF'
Usage:
  scripts/build-local-images.sh [options]

Options:
  --tag TAG             Local image tag (default: SIMPLEMATCH_LOCAL_IMAGE_TAG or local).
  --service NAME        Build one named service, matching, flyway-runner, or
                        risk-matching-e2e-verifier. May repeat.
  --skip-spring         Skip Spring Boot images.
  --skip-native         Skip the native Matching image.
  --skip-flyway         Skip the Flyway runner image.
  --skip-verifier       Skip the RM-1 Risk-to-Matching verifier image.
  --dry-run             Print image build commands without executing them.
  --list                List the canonical local image inventory and exit.
  --help                Show this help.

Environment:
  SIMPLEMATCH_BOOT_RUN_IMAGE
                        Optional local run image for BootBuildImage. Use this
                        when the Docker daemon has transferred multi-platform
                        image metadata that cannot be exported for local amd64
                        builds.
  SIMPLEMATCH_BOOT_PULL_POLICY
                        Optional BootBuildImage pull policy. When a local run
                        image override is set, the default is IF_NOT_PRESENT.
  SIMPLEMATCH_BOOT_RUN_IMAGE_PLATFORM
                        Local run-image platform checked before BootBuildImage
                        (default: linux/amd64).
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --tag)
      image_tag="${2:?--tag requires a value}"
      shift 2
      ;;
    --service)
      selected_services+=("${2:?--service requires a value}")
      shift 2
      ;;
    --skip-spring)
      skip_spring=true
      shift
      ;;
    --skip-native)
      skip_native=true
      shift
      ;;
    --skip-flyway)
      skip_flyway=true
      shift
      ;;
    --skip-verifier)
      skip_verifier=true
      shift
      ;;
    --dry-run)
      dry_run=true
      shift
      ;;
    --list)
      list_only=true
      shift
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    *)
      usage >&2
      printf 'Unknown option: %s\n' "$1" >&2
      exit 2
      ;;
  esac
done

simplematch_local_image_tag_validate "$image_tag" || exit 2
simplematch_local_image_inventory_validate || exit 2
simplematch_local_image_inventory_validate_selection "${selected_services[@]}" || exit 2

run_command() {
  if [[ "$dry_run" == true ]]; then
    printf 'DRY RUN:'
    printf ' %q' "$@"
    printf '\n'
    return
  fi
  "$@"
}

if [[ "$list_only" == true ]]; then
  simplematch_local_image_inventory_emit "$image_tag"
  exit 0
fi

cd "$repo_root"

will_build_spring=false
if [[ "$skip_spring" == false ]]; then
  while IFS='|' read -r image_class service _ _; do
    [[ "$image_class" == spring ]] || continue
    if simplematch_local_image_inventory_service_selected "$service" "${selected_services[@]}"; then
      will_build_spring=true
      break
    fi
  done < <(simplematch_local_image_inventory_entries)
fi

if [[ "$will_build_spring" == true && -n "${SIMPLEMATCH_BOOT_RUN_IMAGE:-}" ]]; then
  run_command "$script_dir/verify-local-boot-run-image.sh" \
    "$SIMPLEMATCH_BOOT_RUN_IMAGE" "${SIMPLEMATCH_BOOT_RUN_IMAGE_PLATFORM:-linux/amd64}"
fi

while IFS='|' read -r image_class service build_source repository; do
  simplematch_local_image_inventory_service_selected "$service" "${selected_services[@]}" || continue
  source_image="${repository}:${image_tag}"

  case "$image_class" in
    spring)
      [[ "$skip_spring" == false ]] || continue
      gradle_image_args=(
        "${build_source}:bootBuildImage"
        "--imageName=${source_image}"
      )
      if [[ -n "${SIMPLEMATCH_BOOT_RUN_IMAGE:-}" ]]; then
        gradle_image_args+=(
          "--runImage=${SIMPLEMATCH_BOOT_RUN_IMAGE}"
          "--pullPolicy=${SIMPLEMATCH_BOOT_PULL_POLICY:-IF_NOT_PRESENT}"
        )
      elif [[ -n "${SIMPLEMATCH_BOOT_PULL_POLICY:-}" ]]; then
        gradle_image_args+=("--pullPolicy=${SIMPLEMATCH_BOOT_PULL_POLICY}")
      fi
      run_command env \
        GRADLE_USER_HOME="${GRADLE_USER_HOME:-$repo_root/out/gradle-home}" \
        "$repo_root/gradlew" --no-daemon \
        "${gradle_image_args[@]}"
      ;;

    flyway)
      [[ "$skip_flyway" == false ]] || continue
      run_command docker build \
        --file "$repo_root/$build_source" \
        --tag "$source_image" \
        "$repo_root"
      ;;

    verification)
      [[ "$skip_verifier" == false ]] || continue
      run_command docker build \
        --file "$repo_root/$build_source" \
        --tag "$source_image" \
        "$repo_root"
      ;;

    native)
      [[ "$skip_native" == false ]] || continue
      run_command docker build \
        --file "$repo_root/$build_source" \
        --tag "$source_image" \
        "$repo_root"
      ;;
  esac
done < <(simplematch_local_image_inventory_entries)
