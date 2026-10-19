#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=lib/flyway-services.sh
source "$repo_root/scripts/lib/flyway-services.sh"

expected_services=(
  account-service
  market-data-projection
  persistence
  query-service
  quickfix-gateway
  risk-service
)

mapfile -t actual_services < <(flyway_known_services)
if [[ "${actual_services[*]}" != "${expected_services[*]}" ]]; then
  echo "Flyway service inventory does not match the expected owner set." >&2
  exit 1
fi

for service in "${expected_services[@]}"; do
  flyway_service_exists "$service"
done

# Exercise the real entry point before it can copy a workspace or start Gradle.
# A wrapper distribution alone does not provide the migration dependencies.
runtime_fixture="$(mktemp -d)"
trap 'rm -rf -- "$runtime_fixture"' EXIT
mkdir -p "$runtime_fixture/wrapper-only/wrapper/dists"
for seed in "$runtime_fixture/missing" "$runtime_fixture/wrapper-only"; do
  if SIMPLEMATCH_FLYWAY_SERVICE_ID=account-service \
      SIMPLEMATCH_GRADLE_CACHE_SEED_DIR="$seed" \
      SIMPLEMATCH_FLYWAY_WORK_DIR="$runtime_fixture/workspace" \
      GRADLE_USER_HOME="$runtime_fixture/gradle" \
      bash "$repo_root/deploy/docker/run-flyway" >"$runtime_fixture/result.log" 2>&1; then
    echo "Flyway accepted an absent or wrapper-only runtime dependency seed." >&2
    exit 1
  fi
  grep -Fq 'Flyway runtime dependency seed is missing or incomplete' \
    "$runtime_fixture/result.log" || {
    cat "$runtime_fixture/result.log" >&2
    echo "Flyway must fail closed on its dependency seed before starting Gradle." >&2
    exit 1
  }
  [[ ! -e "$runtime_fixture/workspace" ]] || {
    echo "Invalid dependency seed must fail before workspace preparation." >&2
    exit 1
  }
done

query_plan_checker="$repo_root/scripts/check-flyway-query-plans.sh"
for service in "${expected_services[@]}"; do
  if ! grep -Fq "  ${service})" "$query_plan_checker"; then
    echo "Flyway query-plan checker has no branch for $service." >&2
    exit 1
  fi
done

grep -Fq 'gradle_user_home="${GRADLE_USER_HOME:-/tmp/gradle}"' \
  "$repo_root/deploy/docker/run-flyway" &&
grep -Fq 'project_cache_dir="${SIMPLEMATCH_GRADLE_PROJECT_CACHE_DIR:-/tmp/gradle-project}"' \
  "$repo_root/deploy/docker/run-flyway" &&
grep -Fq 'gradle_jvm_args="${SIMPLEMATCH_GRADLE_JVM_ARGS:-}"' \
  "$repo_root/deploy/docker/run-flyway" &&
grep -Fq 'kotlin_compiler_execution_strategy="${SIMPLEMATCH_KOTLIN_COMPILER_EXECUTION_STRATEGY:-}"' \
  "$repo_root/deploy/docker/run-flyway" &&
grep -Fq 'gradle_max_workers="${SIMPLEMATCH_GRADLE_MAX_WORKERS:-}"' \
  "$repo_root/deploy/docker/run-flyway" &&
grep -Fq 'exec "$work_dir/gradlew" --no-daemon --offline \' \
  "$repo_root/deploy/docker/run-flyway" &&
grep -Fq '"-Dorg.gradle.jvmargs=$gradle_jvm_args"' \
  "$repo_root/deploy/docker/run-flyway" &&
grep -Fq '"-Dkotlin.compiler.execution.strategy=$kotlin_compiler_execution_strategy"' \
  "$repo_root/deploy/docker/run-flyway" &&
grep -Fq 'gradle_execution_options+=("--max-workers=$gradle_max_workers")' \
  "$repo_root/deploy/docker/run-flyway" &&
grep -Fq '  --gradle-user-home "$gradle_user_home" \' \
  "$repo_root/deploy/docker/run-flyway" &&
grep -Fq '  --project-cache-dir "$project_cache_dir" \' \
  "$repo_root/deploy/docker/run-flyway" || {
  echo "Flyway runner does not explicitly select writable Gradle cache locations." >&2
  exit 1
}

grep -Fq 'gradle_cache_seed_dir="${SIMPLEMATCH_GRADLE_CACHE_SEED_DIR:-/opt/simplematch/gradle-cache}"' \
  "$repo_root/deploy/docker/run-flyway" &&
grep -Fq '"$gradle_cache_seed_dir/flyway-runtime.ready"' \
  "$repo_root/deploy/docker/run-flyway" &&
grep -Fq 'cp -a "$gradle_cache_seed_dir/wrapper/dists/." "$gradle_user_home/wrapper/dists/"' \
  "$repo_root/deploy/docker/run-flyway" &&
grep -Fq 'cp -a "$gradle_cache_seed_dir/caches/." "$gradle_user_home/caches/"' \
  "$repo_root/deploy/docker/run-flyway" || {
  echo "Flyway runner does not seed its writable Gradle cache." >&2
  exit 1
}

grep -Fq '# syntax=docker/dockerfile:1' "$repo_root/deploy/docker/Dockerfile.flyway-runner" &&
grep -Fq 'FROM eclipse-temurin:25-jdk AS gradle-cache' \
  "$repo_root/deploy/docker/Dockerfile.flyway-runner" &&
grep -Fq 'RUN --mount=type=cache,target=/root/.gradle,sharing=locked' \
  "$repo_root/deploy/docker/Dockerfile.flyway-runner" &&
grep -Fq 'COPY --from=gradle-cache --chown=10001:10001 /opt/simplematch/gradle-cache/' \
  "$repo_root/deploy/docker/Dockerfile.flyway-runner" || {
  echo "Flyway image does not prewarm the pinned Gradle distribution." >&2
  exit 1
}
grep -Fq 'COPY --chown=10001:10001 . /workspace/' \
  "$repo_root/deploy/docker/Dockerfile.flyway-runner" &&
grep -Fq 'COPY --from=gradle-cache --chown=10001:10001 /workspace/build-logic/build/' \
  "$repo_root/deploy/docker/Dockerfile.flyway-runner" &&
grep -Fq 'COPY --chown=10001:10001 deploy/docker/run-flyway /app/run-flyway' \
  "$repo_root/deploy/docker/Dockerfile.flyway-runner" || {
  echo "Flyway final-stage inputs must receive ownership while copying." >&2
  exit 1
}
if grep -Eq 'chown[[:space:]]+-R' "$repo_root/deploy/docker/Dockerfile.flyway-runner"; then
  echo "Flyway image must not duplicate its dependency seed through recursive chown." >&2
  exit 1
fi
grep -Fq '/workspace/gradlew --no-daemon prepareFlywayRuntimeAll' \
  "$repo_root/deploy/docker/Dockerfile.flyway-runner" &&
grep -Fq 'cp -a /root/.gradle/caches/.' \
  "$repo_root/deploy/docker/Dockerfile.flyway-runner" || {
  echo "Flyway image does not prepare and package its migration dependency closure." >&2
  exit 1
}
grep -Fq 'ENV SIMPLEMATCH_GRADLE_CACHE_SEED_DIR=/opt/simplematch/gradle-cache' \
  "$repo_root/deploy/docker/Dockerfile.flyway-runner" || {
  echo "Flyway image does not expose its Gradle cache seed path." >&2
  exit 1
}

grep -Fq '# syntax=docker/dockerfile:1' \
  "$repo_root/deploy/docker/Dockerfile.risk-matching-e2e-verifier" &&
grep -Fq 'RUN --mount=type=cache,target=/root/.gradle,sharing=locked' \
  "$repo_root/deploy/docker/Dockerfile.risk-matching-e2e-verifier" || {
  echo "Verifier image does not use a persistent BuildKit Gradle cache." >&2
  exit 1
}

grep -Fq 'work_dir="${SIMPLEMATCH_FLYWAY_WORK_DIR:-/tmp/simplematch-workspace}"' \
  "$repo_root/deploy/docker/run-flyway" &&
grep -Fq 'cp -a /workspace/. "$work_dir/"' \
  "$repo_root/deploy/docker/run-flyway" &&
grep -Fq 'cd "$work_dir"' \
  "$repo_root/deploy/docker/run-flyway" &&
grep -Fq 'exec "$work_dir/gradlew"' \
  "$repo_root/deploy/docker/run-flyway" || {
  echo "Flyway runner does not relocate the Gradle project to a writable workspace." >&2
  exit 1
}

if flyway_service_exists unknown-service; then
  echo "Unknown Flyway service was accepted." >&2
  exit 1
fi

assert_mapping() {
  local service="$1"
  local function_name="$2"
  local expected="$3"
  local actual
  actual="$($function_name "$service")"
  if [[ "$actual" != "$expected" ]]; then
    echo "$function_name($service) returned '$actual', expected '$expected'." >&2
    exit 1
  fi
}

while IFS='|' read -r service project task env schema migration smoke_tables; do
  assert_mapping "$service" flyway_service_project_path "$project"
  assert_mapping "$service" flyway_service_task_prefix "$task"
  assert_mapping "$service" flyway_service_env_prefix "$env"
  assert_mapping "$service" flyway_service_schema "$schema"
  assert_mapping "$service" flyway_service_migration_dir "$migration"
  assert_mapping "$service" flyway_service_smoke_tables "$(printf '%b' "$smoke_tables")"
done <<'MAPPINGS'
account-service|:services:account-service|accountService|ACCOUNT_SERVICE|account_service|services/account-service/src/main/resources/db/migration/account-service|account_limits\naccount_positions\naccount_reservations
market-data-projection|:services:market-data-projection|marketDataProjection|MARKET_DATA_PROJECTION|market_data_projection|services/market-data-projection/src/main/resources/db/migration/market-data-projection|matching_event_inbox\ninstrument_market_data\nmarket_data_events_outbox
persistence|:services:persistence|persistence|PERSISTENCE|persistence|services/persistence/src/main/resources/db/migration/persistence|trades\norder_fills\nmatching_order_projections\nmatching_event_inbox
query-service|:services:query-service|queryService|QUERY_SERVICE|query_service|services/query-service/src/main/resources/db/migration/query-service|order_read_model\nexecution_read_model\naccount_summary_read_model\nactive_market_reference
quickfix-gateway|:services:quickfix-gateway|quickFixGateway|QUICKFIX_GATEWAY|quickfix_gateway|services/quickfix-gateway/src/main/resources/db/migration/quickfix-gateway|matching_event_inbox\nfix_delivery_intents\ngateway_operation_audit
risk-service|:services:risk-service|riskService|RISK_SERVICE|risk_service|services/risk-service/src/main/resources/db/migration/risk-service|admission_journal\noutbox\nlocal_resilience_marker
MAPPINGS

echo "Flyway service mapping functions are valid."
