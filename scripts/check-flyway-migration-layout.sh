#!/usr/bin/env bash

set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd -- "$script_dir/.." && pwd)"
source "$repo_root/scripts/lib/flyway-services.sh"

usage() {
  cat <<'EOF'
Usage:
  scripts/check-flyway-migration-layout.sh --staged
  scripts/check-flyway-migration-layout.sh --range <base-sha> <head-sha>
EOF
}

mode="staged"
base_sha=""
head_sha=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --staged)
      mode="staged"
      shift
      ;;
    --range)
      mode="range"
      base_sha="${2:-}"
      head_sha="${3:-}"
      if [[ -z "$base_sha" || -z "$head_sha" ]]; then
        usage >&2
        exit 2
      fi
      shift 3
      ;;
    *)
      usage >&2
      exit 2
      ;;
  esac
done

collect_changed_files() {
  if [[ "$mode" == "staged" ]]; then
    git -C "$repo_root" diff --cached --name-only --diff-filter=ACMR --relative
    return
  fi

  if [[ -z "$base_sha" || "$base_sha" =~ ^0+$ ]]; then
    git -C "$repo_root" ls-files
    return
  fi

  git -C "$repo_root" diff --name-only --diff-filter=ACMR "$base_sha" "$head_sha"
}

declare -A touched_services=()
status=0
checked_migration_path=0

while IFS= read -r changed_file; do
  [[ -z "$changed_file" ]] && continue

  case "$changed_file" in
    services/*/src/main/resources/db/migration/*)
      checked_migration_path=1
      ;;
    *)
      continue
      ;;
  esac

  if [[ ! "$changed_file" =~ ^services/([^/]+)/src/main/resources/db/migration/(.+)$ ]]; then
    echo "Invalid Flyway migration path: $changed_file" >&2
    status=1
    continue
  fi

  service_name="${BASH_REMATCH[1]}"
  migration_relative_path="${BASH_REMATCH[2]}"

  if ! flyway_service_exists "$service_name"; then
    echo "Unknown Flyway-managed service migration path: $changed_file" >&2
    echo "Add the service to scripts/lib/flyway-services.sh before committing schema files." >&2
    status=1
    continue
  fi

  expected_dir="$(flyway_service_migration_dir "$service_name")"
  if [[ "$changed_file" != "$expected_dir/"* ]]; then
    echo "Flyway migration files for $service_name must live under $expected_dir: $changed_file" >&2
    status=1
    continue
  fi

  migration_filename="${changed_file#"$expected_dir/"}"
  if [[ "$migration_filename" == "$changed_file" || "$migration_filename" == */* ]]; then
    echo "Flyway migration files must sit directly under $expected_dir without nested directories: $changed_file" >&2
    status=1
    continue
  fi

  if [[ ! "$migration_filename" =~ ^V[0-9]+(?:_[0-9]+)*__[a-z0-9][a-z0-9_]*\.sql$ ]]; then
    echo "Invalid Flyway migration filename: $migration_filename" >&2
    echo "Expected pattern: V<version>__lower_snake_case_description.sql" >&2
    status=1
    continue
  fi

  touched_services["$service_name"]=1
done < <(collect_changed_files)

if [[ $checked_migration_path -eq 0 ]]; then
  exit 0
fi

for service_name in "${!touched_services[@]}"; do
  migration_dir="$repo_root/$(flyway_service_migration_dir "$service_name")"
  declare -A seen_versions=()

  while IFS= read -r sql_file; do
    filename="${sql_file##*/}"
    [[ "$filename" =~ ^V([0-9]+(?:_[0-9]+)*)__ ]] || continue
    version="${BASH_REMATCH[1]}"

    if [[ -n "${seen_versions[$version]:-}" ]]; then
      echo "Duplicate Flyway migration version V$version for $service_name:" >&2
      echo "  ${seen_versions[$version]}" >&2
      echo "  $filename" >&2
      status=1
      continue
    fi

    seen_versions[$version]="$filename"
  done < <(find "$migration_dir" -maxdepth 1 -type f -name 'V*.sql' | sort)
done

exit "$status"
