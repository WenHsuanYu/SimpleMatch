#!/usr/bin/env bash

set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd -- "$script_dir/.." && pwd)"

mapfile -t staged_files < <(git -C "$repo_root" diff --cached --name-only --diff-filter=ACMR --relative)

if [[ ${#staged_files[@]} -eq 0 ]]; then
  exit 0
fi

declare -A task_set=()
run_broad_check=0
has_java_relevant_change=0

add_module_tasks() {
  local module_path="$1"
  task_set["$module_path:classes"]=1
  task_set["$module_path:testClasses"]=1

  case "$module_path" in
    :shared-java:simplematch-config|:services:account-service|:services:market-data-projection|:services:marketdata-streamer|:services:persistence|:services:quickfix-gateway|:services:risk-service)
      task_set["$module_path:checkstyleMain"]=1
      ;;
  esac
}

for staged_file in "${staged_files[@]}"; do
  case "$staged_file" in
    build.gradle.kts|settings.gradle.kts|gradlew|gradlew.bat|gradle/*|build-logic/*|proto/*|config/checkstyle/*|config/spotbugs/*)
      has_java_relevant_change=1
      run_broad_check=1
      ;;
    shared-java/simplematch-config/*.gradle.kts|shared-java/simplematch-config/src/*|shared-java/simplematch-contracts/*.gradle.kts|shared-java/simplematch-contracts/src/*|services/account-service/*.gradle.kts|services/account-service/src/*|services/market-data-projection/*.gradle.kts|services/market-data-projection/src/*|services/marketdata-streamer/*.gradle.kts|services/marketdata-streamer/src/*|services/persistence/*.gradle.kts|services/persistence/src/*|services/quickfix-gateway/*.gradle.kts|services/quickfix-gateway/src/*|services/risk-service/*.gradle.kts|services/risk-service/src/*)
      case "$staged_file" in
        *.java|*.kt|*.kts|*.proto)
          has_java_relevant_change=1
          ;;
        *)
          continue
          ;;
      esac

      case "$staged_file" in
        shared-java/simplematch-config/*|shared-java/simplematch-contracts/*)
          run_broad_check=1
          ;;
        services/account-service/*)
          add_module_tasks ':services:account-service'
          ;;
        services/market-data-projection/*)
          add_module_tasks ':services:market-data-projection'
          ;;
        services/marketdata-streamer/*)
          add_module_tasks ':services:marketdata-streamer'
          ;;
        services/persistence/*)
          add_module_tasks ':services:persistence'
          ;;
        services/quickfix-gateway/*)
          add_module_tasks ':services:quickfix-gateway'
          ;;
        services/risk-service/*)
          add_module_tasks ':services:risk-service'
          ;;
      esac
      ;;
  esac
done

if [[ $has_java_relevant_change -eq 0 ]]; then
  exit 0
fi

cd "$repo_root"

if [[ $run_broad_check -eq 1 ]]; then
  echo 'Running broad Gradle pre-commit verification for shared Java or build logic changes...'
  ./gradlew --no-daemon --stacktrace \
    staticAnalysis \
    :shared-java:simplematch-config:testClasses \
    :shared-java:simplematch-contracts:testClasses \
    :services:account-service:testClasses \
    :services:market-data-projection:testClasses \
    :services:marketdata-streamer:testClasses \
    :services:persistence:testClasses \
    :services:quickfix-gateway:testClasses \
    :services:risk-service:testClasses
  exit 0
fi

gradle_tasks=()
while IFS= read -r task_name; do
  [[ -z "$task_name" ]] && continue
  gradle_tasks+=("$task_name")
done < <(printf '%s\n' "${!task_set[@]}" | sort)

if [[ ${#gradle_tasks[@]} -eq 0 ]]; then
  exit 0
fi

echo "Running targeted Gradle pre-commit verification: ${gradle_tasks[*]}"
./gradlew --no-daemon --stacktrace "${gradle_tasks[@]}"
