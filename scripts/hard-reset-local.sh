#!/usr/bin/env bash

set -Eeuo pipefail
IFS=$'\n\t'

# ==============================================================================
# SimpleMatch local hard reset
#
# Removes:
#   - SimpleMatch kind clusters and orphaned kind node containers
#   - SimpleMatch Docker Compose containers / volumes / networks
#   - SimpleMatch local Docker images, including *-boot tags
#   - repository-configured kindest/node image
#   - Paketo / pack build cache volumes
#   - Docker builder / current Buildx builder cache
#   - repository-local Gradle, CMake/native, certification and module build state
#
# Optional:
#   --aggressive-unused-docker
#       Also removes ALL unused Docker containers, images, volumes and networks.
#       This affects other projects.
#
# This script intentionally does NOT:
#   - delete the Git repository
#   - delete source-controlled Market Reference artifacts
#   - stop/remove unrelated running Docker containers
#   - factory-reset Docker Desktop
# ==============================================================================

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd -- "$script_dir/.." && pwd)"

canonical_kind_cluster="simplematch-live"
default_compose_project="simplematch-local-production-like"

kind_config="$repo_root/deploy/kind/simplematch-live.yaml"
compose_file="$repo_root/deploy/compose/kafka-connect.production-like.yml"

dry_run=false
assume_yes=false
aggressive_unused_docker=false
remove_pack_caches=true
remove_project_build_state=true

extra_kind_clusters=()
extra_compose_projects=()

# ------------------------------------------------------------------------------
# Logging / errors
# ------------------------------------------------------------------------------

log() {
  printf '\n=== %s ===\n' "$*"
}

info() {
  printf '%s\n' "$*"
}

warn() {
  printf 'WARNING: %s\n' "$*" >&2
}

die() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

quote_command() {
  printf '$'
  printf ' %q' "$@"
  printf '\n'
}

run() {
  if [[ "$dry_run" == true ]]; then
    quote_command "$@"
    return 0
  fi

  "$@"
}

run_best_effort() {
  if [[ "$dry_run" == true ]]; then
    quote_command "$@"
    return 0
  fi

  "$@" || true
}

contains() {
  local needle="$1"
  shift

  local item
  for item in "$@"; do
    [[ "$item" == "$needle" ]] && return 0
  done

  return 1
}

append_unique() {
  local array_name="$1"
  local value="$2"

  local -n target="$array_name"

  contains "$value" "${target[@]:-}" || target+=("$value")
}

# ------------------------------------------------------------------------------
# CLI
# ------------------------------------------------------------------------------

usage() {
  cat <<'EOF'
Usage:
  scripts/hard-reset-local.sh [options]

Options:
  --yes
      Do not ask for interactive confirmation.

  --dry-run
      Print destructive commands without executing them.

  --aggressive-unused-docker
      After project-scoped cleanup, delete ALL unused Docker:
        - stopped containers
        - unused images
        - unused volumes
        - unused networks

      WARNING: this can remove state belonging to other projects.

  --keep-pack-caches
      Preserve pack-cache-*.build / pack-cache-*.launch volumes.

      By default they are removed because bootBuildImage/Paketo uses them as
      persistent build caches and a true clean rebuild must not reuse them.

  --keep-project-build-state
      Preserve:
        .gradle/
        out/gradle-home/
        out/build/
        out/certification/
        module build/ directories

  --kind-cluster NAME
      Additionally remove a specific kind cluster.
      May be specified multiple times.

  --compose-project NAME
      Additionally remove a specific Docker Compose project.
      May be specified multiple times.

  -h, --help
      Show this help.
EOF
}

while (($# > 0)); do
  case "$1" in
    --yes)
      assume_yes=true
      shift
      ;;

    --dry-run)
      dry_run=true
      shift
      ;;

    --aggressive-unused-docker)
      aggressive_unused_docker=true
      shift
      ;;

    --keep-pack-caches)
      remove_pack_caches=false
      shift
      ;;

    --keep-project-build-state)
      remove_project_build_state=false
      shift
      ;;

    --kind-cluster)
      [[ $# -ge 2 ]] || die '--kind-cluster requires a value'
      extra_kind_clusters+=("$2")
      shift 2
      ;;

    --compose-project)
      [[ $# -ge 2 ]] || die '--compose-project requires a value'
      extra_compose_projects+=("$2")
      shift 2
      ;;

    -h|--help)
      usage
      exit 0
      ;;

    *)
      usage >&2
      die "unknown option: $1"
      ;;
  esac
done

# ------------------------------------------------------------------------------
# Preconditions
# ------------------------------------------------------------------------------

command -v docker >/dev/null 2>&1 ||
  die 'docker is required'

docker info >/dev/null 2>&1 ||
  die 'Docker daemon is not reachable'

[[ -d "$repo_root/.git" ]] ||
  die "this script must live under the SimpleMatch repository: $repo_root"

[[ -f "$repo_root/scripts/build-local-images.sh" ]] ||
  die "repository identity check failed: scripts/build-local-images.sh missing"

if command -v kind >/dev/null 2>&1; then
  have_kind=true
else
  have_kind=false
  warn 'kind is not installed; kind Docker containers will still be removed by label.'
fi

if docker compose version >/dev/null 2>&1; then
  have_compose=true
else
  have_compose=false
  warn 'docker compose plugin is unavailable; label-based cleanup will still run.'
fi

# ------------------------------------------------------------------------------
# Discover resources
# ------------------------------------------------------------------------------

kind_clusters=("$canonical_kind_cluster")
compose_projects=("$default_compose_project")

for cluster in "${extra_kind_clusters[@]:-}"; do
  append_unique kind_clusters "$cluster"
done

for project in "${extra_compose_projects[@]:-}"; do
  append_unique compose_projects "$project"
done

# Discover any other kind clusters whose names clearly belong to SimpleMatch.
if [[ "$have_kind" == true ]]; then
  while IFS= read -r cluster; do
    [[ -n "$cluster" ]] || continue

    if [[ "$cluster" == simplematch* ]]; then
      append_unique kind_clusters "$cluster"
    fi
  done < <(kind get clusters 2>/dev/null || true)
fi

# Discover Compose project names from containers.
while IFS= read -r project; do
  [[ -n "$project" ]] || continue

  if [[ "$project" == simplematch* ]]; then
    append_unique compose_projects "$project"
  fi
done < <(
  docker ps -a \
    --format '{{.Label "com.docker.compose.project"}}' |
    sort -u
)

# Also discover Compose projects that only have leftover volumes.
while IFS= read -r volume; do
  [[ -n "$volume" ]] || continue

  project="$(
    docker volume inspect "$volume" \
      --format '{{index .Labels "com.docker.compose.project"}}' \
      2>/dev/null || true
  )"

  if [[ "$project" == simplematch* ]]; then
    append_unique compose_projects "$project"
  fi
done < <(docker volume ls -q)

# Also discover Compose projects that only have leftover networks.
while IFS= read -r network; do
  [[ -n "$network" ]] || continue

  project="$(
    docker network inspect "$network" \
      --format '{{index .Labels "com.docker.compose.project"}}' \
      2>/dev/null || true
  )"

  if [[ "$project" == simplematch* ]]; then
    append_unique compose_projects "$project"
  fi
done < <(docker network ls -q)

# Read the repository-owned node image instead of hard-coding its version.
kind_node_image=""

if [[ -f "$kind_config" ]]; then
  kind_node_image="$(
    awk '
      $1 == "image:" && $2 ~ /^kindest\/node:/ {
        print $2
        exit
      }
    ' "$kind_config"
  )"
fi

# ------------------------------------------------------------------------------
# Explain destructive scope
# ------------------------------------------------------------------------------

log 'Hard-reset plan'

printf 'Repository:\n  %s\n\n' "$repo_root"

printf 'kind clusters:\n'
printf '  %s\n' "${kind_clusters[@]}"

printf '\nDocker Compose projects:\n'
printf '  %s\n' "${compose_projects[@]}"

printf '\nDocker image repositories removed:\n'
cat <<'EOF'
  simplematch/*
  simplematch-matching:*
  quickfix-gateway:*
EOF

if [[ -n "$kind_node_image" ]]; then
  printf '  %s\n' "$kind_node_image"
fi

printf '\nBuild state:\n'

if [[ "$remove_project_build_state" == true ]]; then
  cat <<EOF
  $repo_root/.gradle
  $repo_root/out/gradle-home
  $repo_root/out/build
  $repo_root/out/certification
  repository module build/ directories
EOF
else
  printf '  PRESERVED (--keep-project-build-state)\n'
fi

printf '\nPaketo cache volumes:\n'

if [[ "$remove_pack_caches" == true ]]; then
  printf '  pack-cache-*.build / pack-cache-*.launch WILL BE REMOVED\n'
else
  printf '  PRESERVED (--keep-pack-caches)\n'
fi

cat <<'EOF'

Docker builder cache:
  Docker builder cache WILL BE PRUNED.
  Current Buildx builder cache WILL BE PRUNED.

  NOTE: Docker build caches are daemon/builder scoped and cannot reliably be
  attributed to only one source repository. Other projects may lose build cache.
EOF

if [[ "$aggressive_unused_docker" == true ]]; then
  cat <<'EOF'

AGGRESSIVE MODE:
  ALL unused Docker containers/images/volumes/networks will also be deleted.
  This can delete persistent state from unrelated projects if those resources
  are currently unused.
EOF
fi

if [[ "$assume_yes" != true && "$dry_run" != true ]]; then
  printf '\nType exactly HARD-RESET-SIMPLEMATCH to continue: '
  read -r confirmation

  [[ "$confirmation" == 'HARD-RESET-SIMPLEMATCH' ]] ||
    die 'confirmation did not match; nothing was removed'
fi

# ------------------------------------------------------------------------------
# 1. Gracefully remove known Compose projects
# ------------------------------------------------------------------------------

log 'Remove Docker Compose projects'

for project in "${compose_projects[@]}"; do
  [[ -n "$project" ]] || continue

  info "Compose project: $project"

  if [[ "$have_compose" == true && -f "$compose_file" ]]; then
    run_best_effort \
      docker compose \
      --project-name "$project" \
      --file "$compose_file" \
      down \
      --volumes \
      --remove-orphans \
      --rmi all
  fi
done

# ------------------------------------------------------------------------------
# 2. Remove kind clusters through kind
# ------------------------------------------------------------------------------

log 'Delete kind clusters'

if [[ "$have_kind" == true ]]; then
  existing_kind_clusters="$(
    kind get clusters 2>/dev/null || true
  )"

  for cluster in "${kind_clusters[@]}"; do
    [[ -n "$cluster" ]] || continue

    if grep -Fxq "$cluster" <<<"$existing_kind_clusters"; then
      run_best_effort kind delete cluster --name "$cluster"
    else
      info "kind cluster already absent: $cluster"
    fi
  done
fi

# ------------------------------------------------------------------------------
# 3. Remove orphaned kind node containers
#
# This is necessary when kind metadata/container state is inconsistent and
# `kind delete cluster` cannot finish normally.
# ------------------------------------------------------------------------------

log 'Remove orphaned SimpleMatch kind containers'

while IFS= read -r container; do
  [[ -n "$container" ]] || continue

  cluster="$(
    docker inspect "$container" \
      --format '{{index .Config.Labels "io.x-k8s.kind.cluster"}}' \
      2>/dev/null || true
  )"

  [[ "$cluster" == simplematch* ]] || continue

  info "Removing orphaned kind container $container (cluster=$cluster)"
  run docker rm --force --volumes "$container"
done < <(
  docker ps -aq \
    --filter 'label=io.x-k8s.kind.cluster'
)

# ------------------------------------------------------------------------------
# 4. Remove leftover Compose containers by label
# ------------------------------------------------------------------------------

log 'Remove leftover SimpleMatch Compose containers'

while IFS= read -r container; do
  [[ -n "$container" ]] || continue

  project="$(
    docker inspect "$container" \
      --format '{{index .Config.Labels "com.docker.compose.project"}}' \
      2>/dev/null || true
  )"

  [[ "$project" == simplematch* ]] || continue

  info "Removing container $container (compose project=$project)"
  run docker rm --force --volumes "$container"
done < <(
  docker ps -aq \
    --filter 'label=com.docker.compose.project'
)

# ------------------------------------------------------------------------------
# 5. Remove leftover Compose volumes by label
# ------------------------------------------------------------------------------

log 'Remove leftover SimpleMatch Compose volumes'

while IFS= read -r volume; do
  [[ -n "$volume" ]] || continue

  project="$(
    docker volume inspect "$volume" \
      --format '{{index .Labels "com.docker.compose.project"}}' \
      2>/dev/null || true
  )"

  [[ "$project" == simplematch* ]] || continue

  info "Removing volume $volume (compose project=$project)"
  run docker volume rm --force "$volume"
done < <(docker volume ls -q)

# ------------------------------------------------------------------------------
# 6. Remove Paketo / pack cache volumes
#
# These volumes generally have no project labels. We therefore cannot prove
# which repository created them. The default hard-reset policy removes all
# pack-cache-* because keeping them would violate clean-build semantics.
# ------------------------------------------------------------------------------

if [[ "$remove_pack_caches" == true ]]; then
  log 'Remove Paketo / pack build cache volumes'

  while IFS= read -r volume; do
    [[ -n "$volume" ]] || continue
    [[ "$volume" == pack-cache-* ]] || continue

    users="$(
      docker ps -aq \
        --filter "volume=$volume" 2>/dev/null || true
    )"

    if [[ -n "$users" ]]; then
      warn "pack cache is still attached to container(s), refusing automatic removal: $volume"
      docker ps -a \
        --filter "volume=$volume" \
        --format '  {{.ID}}  {{.Image}}  {{.Names}}' >&2
      continue
    fi

    info "Removing pack cache volume: $volume"
    run docker volume rm "$volume"
  done < <(docker volume ls -q)
fi

# ------------------------------------------------------------------------------
# 7. Remove leftover Compose networks by label
# ------------------------------------------------------------------------------

log 'Remove leftover SimpleMatch Compose networks'

while IFS= read -r network; do
  [[ -n "$network" ]] || continue

  # Never attempt Docker's built-in networks.
  case "$network" in
    bridge|host|none)
      continue
      ;;
  esac

  project="$(
    docker network inspect "$network" \
      --format '{{index .Labels "com.docker.compose.project"}}' \
      2>/dev/null || true
  )"

  [[ "$project" == simplematch* ]] || continue

  info "Removing network $network (compose project=$project)"
  run_best_effort docker network rm "$network"
done < <(docker network ls --format '{{.Name}}')

# ------------------------------------------------------------------------------
# 8. Remove all SimpleMatch image tags
#
# This deliberately matches repository names, not just :local, so old :local,
# :local-boot and temporary tags from previous runs are not retained.
# ------------------------------------------------------------------------------

log 'Remove SimpleMatch Docker images'

simplematch_image_refs=()

while IFS='|' read -r repository tag; do
  [[ -n "$repository" ]] || continue

  case "$repository" in
    simplematch/*|simplematch-matching|quickfix-gateway)
      [[ "$tag" == '<none>' ]] && continue
      simplematch_image_refs+=("${repository}:${tag}")
      ;;
  esac
done < <(
  docker image ls \
    --format '{{.Repository}}|{{.Tag}}'
)

if ((${#simplematch_image_refs[@]} > 0)); then
  printf 'Removing:\n'
  printf '  %s\n' "${simplematch_image_refs[@]}"
  run docker image rm --force "${simplematch_image_refs[@]}"
else
  info 'No tagged SimpleMatch images remain.'
fi

# ------------------------------------------------------------------------------
# 9. Remove repository-owned kind node base image
# ------------------------------------------------------------------------------

log 'Remove kind node image'

if [[ -n "$kind_node_image" ]]; then
  if docker image inspect "$kind_node_image" >/dev/null 2>&1; then
    run docker image rm --force "$kind_node_image"
  else
    info "Already absent: $kind_node_image"
  fi
else
  warn "Could not determine kindest/node image from $kind_config"
fi

# ------------------------------------------------------------------------------
# 10. Remove project-local build/evidence state
# ------------------------------------------------------------------------------

if [[ "$remove_project_build_state" == true ]]; then
  log 'Remove repository-local generated state'

  generated_paths=(
    "$repo_root/.gradle"
    "$repo_root/out/gradle-home"
    "$repo_root/out/build"
    "$repo_root/out/certification"
  )

  for path in "${generated_paths[@]}"; do
    if [[ -e "$path" ]]; then
      info "Removing $path"
      run rm -rf -- "$path"
    fi
  done

  # Gradle module build directories can exist throughout the multi-project tree.
  # Do not descend into .git.
  while IFS= read -r -d '' build_dir; do
    info "Removing module build directory: $build_dir"
    run rm -rf -- "$build_dir"
  done < <(
    find "$repo_root" \
      -path "$repo_root/.git" -prune -o \
      -type d -name build -print0
  )
fi

# ------------------------------------------------------------------------------
# 11. Prune Docker build caches
#
# This is intentionally global for the currently selected Docker daemon/builder.
# Docker does not expose a reliable repository ownership boundary for these
# caches.
# ------------------------------------------------------------------------------

log 'Prune Docker build cache'

run docker builder prune --all --force

if docker buildx version >/dev/null 2>&1; then
  run docker buildx prune --all --force
else
  info 'docker buildx is unavailable; skipping Buildx cache prune.'
fi

# ------------------------------------------------------------------------------
# 12. Optional aggressive daemon-wide cleanup
# ------------------------------------------------------------------------------

if [[ "$aggressive_unused_docker" == true ]]; then
  log 'Aggressive cleanup of ALL unused Docker resources'

  warn 'Removing unused Docker state across ALL projects.'

  run docker container prune --force
  run docker image prune --all --force
  run docker volume prune --all --force
  run docker network prune --force

  # Prune again because removing images/containers can expose additional cache.
  run docker builder prune --all --force

  if docker buildx version >/dev/null 2>&1; then
    run docker buildx prune --all --force
  fi
fi

# ------------------------------------------------------------------------------
# 13. Verification
# ------------------------------------------------------------------------------

log 'Verify reset'

verification_failed=false

if [[ "$have_kind" == true ]]; then
  current_clusters="$(kind get clusters 2>/dev/null || true)"

  for cluster in "${kind_clusters[@]}"; do
    if grep -Fxq "$cluster" <<<"$current_clusters"; then
      warn "kind cluster still exists: $cluster"
      verification_failed=true
    fi
  done
fi

# Orphaned SimpleMatch kind containers.
while IFS= read -r container; do
  [[ -n "$container" ]] || continue

  cluster="$(
    docker inspect "$container" \
      --format '{{index .Config.Labels "io.x-k8s.kind.cluster"}}' \
      2>/dev/null || true
  )"

  if [[ "$cluster" == simplematch* ]]; then
    warn "SimpleMatch kind container remains: $container ($cluster)"
    verification_failed=true
  fi
done < <(
  docker ps -aq \
    --filter 'label=io.x-k8s.kind.cluster'
)

# Compose containers.
while IFS= read -r container; do
  [[ -n "$container" ]] || continue

  project="$(
    docker inspect "$container" \
      --format '{{index .Config.Labels "com.docker.compose.project"}}' \
      2>/dev/null || true
  )"

  if [[ "$project" == simplematch* ]]; then
    warn "SimpleMatch Compose container remains: $container ($project)"
    verification_failed=true
  fi
done < <(
  docker ps -aq \
    --filter 'label=com.docker.compose.project'
)

# Compose volumes.
while IFS= read -r volume; do
  [[ -n "$volume" ]] || continue

  project="$(
    docker volume inspect "$volume" \
      --format '{{index .Labels "com.docker.compose.project"}}' \
      2>/dev/null || true
  )"

  if [[ "$project" == simplematch* ]]; then
    warn "SimpleMatch Compose volume remains: $volume ($project)"
    verification_failed=true
  fi
done < <(docker volume ls -q)

# Paketo cache volumes.
if [[ "$remove_pack_caches" == true ]]; then
  while IFS= read -r volume; do
    [[ "$volume" == pack-cache-* ]] || continue

    warn "Paketo cache volume remains: $volume"
    verification_failed=true
  done < <(docker volume ls -q)
fi

# Images.
while IFS='|' read -r repository tag; do
  case "$repository" in
    simplematch/*|simplematch-matching|quickfix-gateway)
      warn "SimpleMatch Docker image remains: ${repository}:${tag}"
      verification_failed=true
      ;;
  esac
done < <(
  docker image ls \
    --format '{{.Repository}}|{{.Tag}}'
)

if [[ -n "$kind_node_image" ]] &&
   docker image inspect "$kind_node_image" >/dev/null 2>&1; then
  warn "kind node image remains: $kind_node_image"
  verification_failed=true
fi

# Project generated state.
if [[ "$remove_project_build_state" == true ]]; then
  for path in \
    "$repo_root/.gradle" \
    "$repo_root/out/gradle-home" \
    "$repo_root/out/build" \
    "$repo_root/out/certification"
  do
    if [[ -e "$path" ]]; then
      warn "generated project path remains: $path"
      verification_failed=true
    fi
  done
fi

# ------------------------------------------------------------------------------
# Final inventory
# ------------------------------------------------------------------------------

log 'Docker inventory after reset'

docker system df || true

printf '\nContainers still running:\n'
docker ps \
  --format 'table {{.ID}}\t{{.Image}}\t{{.Names}}' ||
  true

printf '\nRemaining images:\n'
docker image ls ||
  true

printf '\nRemaining volumes:\n'
docker volume ls ||
  true

if [[ "$verification_failed" == true ]]; then
  printf '\n'
  die 'hard reset completed, but residual state listed above still requires attention'
fi

log 'SimpleMatch hard reset completed successfully'

if [[ "$aggressive_unused_docker" != true ]]; then
  cat <<'EOF'

Project-scoped reset is clean.

Unused Docker resources belonging to other projects may still exist.
For a daemon-wide unused-resource purge, rerun with:

  scripts/hard-reset-local.sh \
    --aggressive-unused-docker \
    --yes
EOF
fi
