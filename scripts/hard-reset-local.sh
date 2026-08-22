#!/usr/bin/env bash
set -Eeuo pipefail
IFS=$'\n\t'

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd -- "$script_dir/.." && pwd)"
# shellcheck source=scripts/lib/local-common.sh
source "$script_dir/lib/local-common.sh"
# shellcheck source=scripts/lib/local-registry.sh
source "$script_dir/lib/local-registry.sh"

canonical_kind_cluster="${SIMPLEMATCH_KIND_CLUSTER_NAME:-simplematch-live}"
default_compose_project="${SIMPLEMATCH_CERTIFICATION_COMPOSE_PROJECT:-simplematch-local-production-like}"
compose_file="$repo_root/deploy/compose/kafka-connect.production-like.yml"
registry_manager="$script_dir/manage-local-registry.sh"

assume_yes=false
aggressive_unused_docker=false
remove_project_build_state=true
purge_registry=true
SIMPLEMATCH_DRY_RUN=false
extra_kind_clusters=()
extra_compose_projects=()

usage() {
  cat <<'EOF_USAGE'
Usage:
  scripts/hard-reset-local.sh [options]

Project hard reset removes only attributable SimpleMatch local runtime state:
the canonical kind cluster, the canonical local-certification Compose project,
explicitly selected additional SimpleMatch clusters/projects, the local registry
cache, unreferenced SimpleMatch-tagged host images, and generated repository
build/evidence state. It does not manually mutate containerd snapshot metadata;
deleting a verified kind cluster removes those node-local caches.

If another SimpleMatch kind cluster or Compose project exists, hard reset refuses
to start until that runtime is either stopped separately or explicitly added with
--kind-cluster / --compose-project. This prevents shared registry or generated
state from being removed underneath an unselected SimpleMatch runtime.

Daemon-wide Pack/BuildKit caches and unrelated Docker resources are preserved by
default because they cannot be reliably attributed to this repository.

Options:
  --yes
      Do not ask for interactive confirmation.
  --dry-run
      Print destructive commands without executing them.
  --aggressive-unused-docker
      Also remove ALL globally unused Docker containers, images, volumes,
      networks, and builder/buildx caches. This can affect other projects.
  --keep-project-build-state
      Preserve .gradle/, out/gradle-home/, out/build/, out/certification/ and
      module build/ directories.
  --keep-registry-cache
      Remove the registry container but preserve its image-data volume.
  --kind-cluster NAME
      Additionally remove a SimpleMatch-named kind cluster. May repeat.
  --compose-project NAME
      Additionally remove a SimpleMatch-named Compose project. May repeat.
  -h, --help
      Show this help.
EOF_USAGE
}

while (($# > 0)); do
  case "$1" in
    --yes) assume_yes=true; shift ;;
    --dry-run) SIMPLEMATCH_DRY_RUN=true; shift ;;
    --aggressive-unused-docker) aggressive_unused_docker=true; shift ;;
    --keep-project-build-state) remove_project_build_state=false; shift ;;
    --keep-registry-cache) purge_registry=false; shift ;;
    --kind-cluster)
      extra_kind_clusters+=("${2:?--kind-cluster requires a value}")
      shift 2
      ;;
    --compose-project)
      extra_compose_projects+=("${2:?--compose-project requires a value}")
      shift 2
      ;;
    -h|--help) usage; exit 0 ;;
    *) usage >&2; simplematch_die "unknown option: $1" ;;
  esac
done

simplematch_require_command docker
docker info >/dev/null 2>&1 || simplematch_die 'Docker daemon is not reachable'
[[ -d "$repo_root/.git" ]] || simplematch_die "not inside the SimpleMatch repository: $repo_root"
[[ -f "$script_dir/build-local-images.sh" ]] || simplematch_die 'repository identity check failed'
[[ -f "$registry_manager" ]] || simplematch_die "registry manager does not exist: $registry_manager"

kind_clusters=("$canonical_kind_cluster")
compose_projects=("$default_compose_project")

for cluster in "${extra_kind_clusters[@]:-}"; do
  [[ "$cluster" == simplematch* ]] || simplematch_die "refusing non-SimpleMatch kind cluster: $cluster"
  simplematch_append_unique kind_clusters "$cluster"
done
for project in "${extra_compose_projects[@]:-}"; do
  [[ "$project" == simplematch* ]] || simplematch_die "refusing non-SimpleMatch Compose project: $project"
  simplematch_append_unique compose_projects "$project"
done

# Shared registry/build state means an unselected SimpleMatch runtime makes the
# destructive scope ambiguous. Discover from Docker labels rather than only from
# kind/Compose CLIs so broken or partially removed runtimes are still protected.
unselected_kind_clusters=()
while IFS= read -r container; do
  [[ -n "$container" ]] || continue
  cluster="$(docker inspect "$container" --format '{{index .Config.Labels "io.x-k8s.kind.cluster"}}' 2>/dev/null || true)"
  [[ "$cluster" == simplematch* ]] || continue
  simplematch_contains "$cluster" "${kind_clusters[@]}" ||
    simplematch_append_unique unselected_kind_clusters "$cluster"
done < <(docker ps -aq --filter 'label=io.x-k8s.kind.cluster')

unselected_compose_projects=()
while IFS= read -r container; do
  [[ -n "$container" ]] || continue
  project="$(docker inspect "$container" --format '{{index .Config.Labels "com.docker.compose.project"}}' 2>/dev/null || true)"
  [[ "$project" == simplematch* ]] || continue
  simplematch_contains "$project" "${compose_projects[@]}" ||
    simplematch_append_unique unselected_compose_projects "$project"
done < <(docker ps -aq --filter 'label=com.docker.compose.project')

if ((${#unselected_kind_clusters[@]} > 0 || ${#unselected_compose_projects[@]} > 0)); then
  for cluster in "${unselected_kind_clusters[@]}"; do
    simplematch_warn "unselected SimpleMatch kind cluster: $cluster (add --kind-cluster $cluster to remove it)"
  done
  for project in "${unselected_compose_projects[@]}"; do
    simplematch_warn "unselected SimpleMatch Compose project: $project (add --compose-project $project to remove it)"
  done
  simplematch_die 'hard reset scope is ambiguous while unselected SimpleMatch runtimes exist'
fi

image_has_container_reference() {
  local image_reference="$1"
  [[ -n "$(docker ps -aq --filter "ancestor=${image_reference}" 2>/dev/null || true)" ]]
}

kind_cluster_has_container_reference() {
  local cluster_name="$1"
  [[ -n "$(docker ps -aq --filter "label=io.x-k8s.kind.cluster=${cluster_name}" 2>/dev/null || true)" ]]
}

remove_local_registry() {
  local -a args=(delete)
  [[ "$purge_registry" == true ]] && args+=(--purge-data)
  [[ "$SIMPLEMATCH_DRY_RUN" == true ]] && args+=(--dry-run)
  simplematch_run bash "$registry_manager" "${args[@]}"
}

simplematch_log 'Hard-reset plan'
printf 'Repository: %s\n' "$repo_root"
printf 'kind clusters:\n'; printf '  %s\n' "${kind_clusters[@]}"
printf 'Compose projects:\n'; printf '  %s\n' "${compose_projects[@]}"
printf 'Registry: %s (%s) data=%s purge_data=%s\n' \
  "$SIMPLEMATCH_LOCAL_REGISTRY_NAME" "$(simplematch_registry_endpoint)" \
  "$SIMPLEMATCH_LOCAL_REGISTRY_DATA_VOLUME" "$purge_registry"
printf 'SimpleMatch image repositories: simplematch/*, simplematch-matching:*, quickfix-gateway:*\n'
printf 'Daemon-wide unused Docker cleanup: %s\n' "$aggressive_unused_docker"

if [[ "$assume_yes" != true && "$SIMPLEMATCH_DRY_RUN" != true ]]; then
  printf 'Type exactly HARD-RESET-SIMPLEMATCH to continue: '
  read -r confirmation
  [[ "$confirmation" == HARD-RESET-SIMPLEMATCH ]] || simplematch_die 'confirmation did not match; nothing was removed'
fi

simplematch_log 'Remove selected Docker Compose projects'
if docker compose version >/dev/null 2>&1 && [[ -f "$compose_file" ]]; then
  for project in "${compose_projects[@]}"; do
    [[ -n "$project" ]] || continue
    simplematch_run_best_effort docker compose \
      --project-name "$project" --file "$compose_file" \
      down --volumes --remove-orphans
  done
else
  simplematch_warn 'docker compose unavailable; selected label cleanup will handle residual containers'
fi

simplematch_log 'Delete selected kind clusters'
current_clusters=""
if command -v kind >/dev/null 2>&1; then
  if ! current_clusters="$(kind get clusters 2>/dev/null)"; then
    simplematch_warn 'kind cluster discovery failed; Docker ownership evidence will still protect canonical deletion'
    current_clusters=""
  fi
fi
for cluster in "${kind_clusters[@]}"; do
  [[ -n "$cluster" ]] || continue

  if [[ "$cluster" == "$canonical_kind_cluster" ]]; then
    # Canonical deletion must always cross the manager safety boundary. Docker
    # ownership evidence is checked independently from `kind get clusters` so a
    # missing/broken kind CLI cannot turn canonical nodes into generic orphans.
    if kind_cluster_has_container_reference "$cluster" || grep -Fxq "$cluster" <<<"$current_clusters"; then
      args=(delete)
      [[ "$SIMPLEMATCH_DRY_RUN" == true ]] && args+=(--dry-run)
      simplematch_run bash "$script_dir/manage-simplematch-live.sh" "${args[@]}"
    else
      simplematch_info "canonical kind cluster already absent: $cluster"
    fi
    continue
  fi

  if [[ -n "$current_clusters" ]] && grep -Fxq "$cluster" <<<"$current_clusters"; then
    simplematch_run_best_effort kind delete cluster --name "$cluster"
  fi
done

simplematch_log 'Remove orphaned containers for selected kind clusters'
while IFS= read -r container; do
  [[ -n "$container" ]] || continue
  cluster="$(docker inspect "$container" --format '{{index .Config.Labels "io.x-k8s.kind.cluster"}}' 2>/dev/null || true)"
  simplematch_contains "$cluster" "${kind_clusters[@]}" || continue
  simplematch_run docker rm --force --volumes "$container"
done < <(docker ps -aq --filter 'label=io.x-k8s.kind.cluster')

simplematch_log 'Remove residual resources for selected Compose projects'
while IFS= read -r container; do
  [[ -n "$container" ]] || continue
  project="$(docker inspect "$container" --format '{{index .Config.Labels "com.docker.compose.project"}}' 2>/dev/null || true)"
  simplematch_contains "$project" "${compose_projects[@]}" || continue
  simplematch_run docker rm --force --volumes "$container"
done < <(docker ps -aq --filter 'label=com.docker.compose.project')

while IFS= read -r volume; do
  [[ -n "$volume" ]] || continue
  project="$(docker volume inspect "$volume" --format '{{index .Labels "com.docker.compose.project"}}' 2>/dev/null || true)"
  simplematch_contains "$project" "${compose_projects[@]}" || continue
  simplematch_run docker volume rm --force "$volume"
done < <(docker volume ls -q)

while IFS= read -r network; do
  case "$network" in bridge|host|none|'') continue ;; esac
  project="$(docker network inspect "$network" --format '{{index .Labels "com.docker.compose.project"}}' 2>/dev/null || true)"
  simplematch_contains "$project" "${compose_projects[@]}" || continue
  simplematch_run_best_effort docker network rm "$network"
done < <(docker network ls --format '{{.Name}}')

simplematch_log 'Remove local registry through registry manager'
remove_local_registry

simplematch_log 'Remove unreferenced SimpleMatch host images'
image_refs=()
while IFS='|' read -r repository tag; do
  case "$repository" in
    simplematch/*|simplematch-matching|quickfix-gateway)
      [[ "$tag" == '<none>' ]] && continue
      image_reference="${repository}:${tag}"
      if image_has_container_reference "$image_reference"; then
        simplematch_warn "keeping SimpleMatch image referenced by a remaining container: $image_reference"
        continue
      fi
      image_refs+=("$image_reference")
      ;;
  esac
done < <(docker image ls --format '{{.Repository}}|{{.Tag}}')
if ((${#image_refs[@]} > 0)); then
  simplematch_run docker image rm "${image_refs[@]}"
fi

if [[ "$remove_project_build_state" == true ]]; then
  simplematch_log 'Remove repository-generated state'
  for path in \
    "$repo_root/.gradle" \
    "$repo_root/out/gradle-home" \
    "$repo_root/out/build" \
    "$repo_root/out/certification"
  do
    [[ -e "$path" ]] && simplematch_run rm -rf -- "$path"
  done
  while IFS= read -r -d '' build_dir; do
    simplematch_run rm -rf -- "$build_dir"
  done < <(find "$repo_root" -path "$repo_root/.git" -prune -o -type d -name build -print0)
fi

if [[ "$aggressive_unused_docker" == true ]]; then
  simplematch_log 'Aggressive daemon-wide unused-resource cleanup'
  simplematch_warn 'This opt-in cleanup can remove resources and builder caches owned by other projects.'
  simplematch_run docker container prune --force
  simplematch_run docker image prune --all --force
  simplematch_run docker volume prune --all --force
  simplematch_run docker network prune --force
  simplematch_run docker builder prune --all --force
  if docker buildx version >/dev/null 2>&1; then
    simplematch_run docker buildx prune --all --force
  fi
fi

if [[ "$SIMPLEMATCH_DRY_RUN" == true ]]; then
  simplematch_log 'Hard-reset dry run completed; no state was changed'
  exit 0
fi

simplematch_log 'Verify reset'
verification_failed=false
if command -v kind >/dev/null 2>&1; then
  remaining_clusters="$(kind get clusters 2>/dev/null || true)"
  for cluster in "${kind_clusters[@]}"; do
    if grep -Fxq "$cluster" <<<"$remaining_clusters"; then
      simplematch_warn "selected kind cluster remains: $cluster"
      verification_failed=true
    fi
  done
fi
if simplematch_registry_container_exists; then
  simplematch_warn "local registry container remains: $SIMPLEMATCH_LOCAL_REGISTRY_NAME"
  verification_failed=true
fi
if [[ "$purge_registry" == true ]] && docker volume inspect "$SIMPLEMATCH_LOCAL_REGISTRY_DATA_VOLUME" >/dev/null 2>&1; then
  simplematch_warn "local registry data volume remains: $SIMPLEMATCH_LOCAL_REGISTRY_DATA_VOLUME"
  verification_failed=true
fi
while IFS='|' read -r repository tag; do
  case "$repository" in
    simplematch/*|simplematch-matching|quickfix-gateway)
      [[ "$tag" == '<none>' ]] && continue
      image_reference="${repository}:${tag}"
      if image_has_container_reference "$image_reference"; then
        simplematch_info "Retained referenced SimpleMatch image: $image_reference"
      else
        simplematch_warn "unreferenced SimpleMatch image remains: $image_reference"
        verification_failed=true
      fi
      ;;
  esac
done < <(docker image ls --format '{{.Repository}}|{{.Tag}}')

while IFS= read -r container; do
  [[ -n "$container" ]] || continue
  project="$(docker inspect "$container" --format '{{index .Config.Labels "com.docker.compose.project"}}' 2>/dev/null || true)"
  if simplematch_contains "$project" "${compose_projects[@]}"; then
    simplematch_warn "selected Compose container remains: $container ($project)"
    verification_failed=true
  fi
done < <(docker ps -aq --filter 'label=com.docker.compose.project')

if [[ "$remove_project_build_state" == true ]]; then
  for path in \
    "$repo_root/.gradle" \
    "$repo_root/out/gradle-home" \
    "$repo_root/out/build" \
    "$repo_root/out/certification"
  do
    if [[ -e "$path" ]]; then
      simplematch_warn "generated project path remains: $path"
      verification_failed=true
    fi
  done
fi

docker system df || true
[[ "$verification_failed" == false ]] || simplematch_die 'hard reset left residual selected SimpleMatch state'
simplematch_log 'SimpleMatch hard reset completed successfully'
