#!/usr/bin/env bash

set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd -- "$script_dir/.." && pwd)"

image_tag="${SIMPLEMATCH_LOCAL_IMAGE_TAG:-local}"
dry_run=false
list_only=false
skip_spring=false
skip_native=false
skip_flyway=false
selected_services=()

spring_images=(
  "account-service|:services:account-service|simplematch/account-service"
  "risk-service|:services:risk-service|simplematch/risk-service"
  "persistence|:services:persistence|simplematch/persistence"
  "market-data-projection|:services:market-data-projection|simplematch/market-data-projection"
  "marketdata-publisher|:services:marketdata-publisher|simplematch/marketdata-publisher"
  "marketdata-streamer|:services:marketdata-streamer|simplematch/marketdata-streamer"
  "query-service|:services:query-service|simplematch/query-service"
  "quickfix-gateway|:services:quickfix-gateway|quickfix-gateway"
)

usage() {
  cat <<'EOF'
Usage:
  scripts/build-local-images.sh [options]

Options:
  --tag TAG             Local image tag (default: SIMPLEMATCH_LOCAL_IMAGE_TAG or local).
  --service NAME        Build one named service, matching, or flyway-runner.
  --skip-spring         Skip Spring Boot images.
  --skip-native         Skip the native Matching image.
  --skip-flyway         Skip the Flyway runner image.
  --dry-run             Print image build commands without executing them.
  --list                List the local image inventory and exit.
  --help                Show this help.
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

image_name() {
  printf '%s:%s' "$1" "$image_tag"
}

selected() {
  local candidate="$1"
  if [[ ${#selected_services[@]} -eq 0 ]]; then
    return 0
  fi
  local selected_service
  for selected_service in "${selected_services[@]}"; do
    [[ "$selected_service" == "$candidate" ]] && return 0
  done
  return 1
}

print_inventory() {
  local entry service project image
  for entry in "${spring_images[@]}"; do
    IFS='|' read -r service project image <<<"$entry"
    printf 'spring|%s|%s|%s\n' "$service" "$project" "$(image_name "$image")"
  done
  printf 'flyway|flyway-runner|deploy/docker/Dockerfile.flyway-runner|%s\n' \
    "$(image_name simplematch/flyway-runner)"
  printf 'native|matching|deploy/docker/Dockerfile.matching|%s\n' \
    "$(image_name simplematch-matching)"
}

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
  print_inventory
  exit 0
fi

cd "$repo_root"

if [[ "$skip_spring" == false ]]; then
  for entry in "${spring_images[@]}"; do
    IFS='|' read -r service project image <<<"$entry"
    selected "$service" || continue
    run_command env \
      GRADLE_USER_HOME="${GRADLE_USER_HOME:-$repo_root/out/gradle-home}" \
      "$repo_root/gradlew" --no-daemon \
      "${project}:bootBuildImage" "--imageName=$(image_name "$image")"
  done
fi

if [[ "$skip_flyway" == false ]] && selected flyway-runner; then
  run_command docker build \
    --file "$repo_root/deploy/docker/Dockerfile.flyway-runner" \
    --tag "$(image_name simplematch/flyway-runner)" \
    "$repo_root"
fi

if [[ "$skip_native" == false ]] && selected matching; then
  run_command docker build \
    --file "$repo_root/deploy/docker/Dockerfile.matching" \
    --tag "$(image_name simplematch-matching)" \
    "$repo_root"
fi
