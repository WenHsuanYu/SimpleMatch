#!/usr/bin/env bash

# Runtime provenance authority for focused CDC diagnostics. Keep this module
# separate from verifier-specific helpers so a verifier fix does not invalidate
# an otherwise unchanged retained namespace. The module itself is an input to
# the runtime scope: changing the path set or hashing algorithm therefore
# requires a fresh source-aligned deployment.

simplematch_certification_runtime_source_paths() {
  printf '%s\n' \
    . \
    ':(exclude)graphify-out/**' \
    ':(exclude)docs/**' \
    ':(exclude)*.md' \
    ':(exclude)**/*.md'
}

simplematch_certification_source_revision() {
  local repo_root="$1"
  local untracked_source
  local -a runtime_source_paths

  mapfile -t runtime_source_paths < <(simplematch_certification_runtime_source_paths)

  if ! git -C "$repo_root" diff --quiet --ignore-submodules -- \
        "${runtime_source_paths[@]}" ||
     ! git -C "$repo_root" diff --cached --quiet --ignore-submodules -- \
        "${runtime_source_paths[@]}"; then
    printf '%s\n' \
      'certification runtime source has tracked changes; commit or restore them before certification.' \
      >&2
    return 1
  fi

  untracked_source="$(
    git -C "$repo_root" ls-files --others --exclude-standard -- \
      "${runtime_source_paths[@]}"
  )" || return 1
  if [[ -n "$untracked_source" ]]; then
    printf '%s\n' \
      'certification runtime source has untracked files; commit, ignore, or remove them before certification.' \
      >&2
    printf '%s\n' "$untracked_source" >&2
    return 1
  fi

  git -C "$repo_root" rev-parse HEAD
}

simplematch_certification_cdc_runtime_source_paths() {
  printf '%s\n' \
    scripts/lib/local-certification-runtime-provenance.sh \
    scripts/lib/local-certification-provenance.sh \
    scripts/lib/local-common.sh \
    scripts/lib/local-certification-phase-graph.sh \
    scripts/lib/local-certification-fingerprint.sh \
    scripts/lib/local-certification-evidence.sh \
    scripts/lib/local-certification-planner.sh \
    scripts/lib/local-certification-images.sh \
    scripts/lib/local-certification-kafka.sh \
    scripts/lib/local-certification-artifacts.sh \
    scripts/lib/local-certification-framework.sh \
    scripts/lib/local-certification-job.sh \
    scripts/lib/local-certification-connect.sh \
    scripts/lib/local-certification-kubernetes.sh \
    scripts/lib/local-certification-run.sh \
    scripts/lib/local-certification-bootstrap.sh \
    scripts/lib/local-certification-workloads.sh \
    scripts/lib/local-image-inventory.sh \
    scripts/lib/local-image-transport.sh \
    scripts/lib/local-kind.sh \
    scripts/build-local-images.sh \
    scripts/render-local-kubernetes-manifest.sh \
    scripts/run-local-production-like-certification.sh \
    .dockerignore gradlew gradlew.bat \
    config \
    build.gradle.kts settings.gradle.kts gradle \
    build-logic/src/main build-logic/build.gradle.kts build-logic/gradle.lockfile \
    shared-java/market-reference-contract/src/main \
    shared-java/market-reference-contract/build.gradle.kts \
    shared-java/market-reference-contract/gradle.lockfile \
    shared-java/simplematch-config/src/main \
    shared-java/simplematch-config/build.gradle.kts \
    shared-java/simplematch-config/gradle.lockfile \
    shared-java/simplematch-contracts/src/main \
    shared-java/simplematch-contracts/build.gradle.kts \
    shared-java/simplematch-contracts/gradle.lockfile \
    services/account-service/src/main services/account-service/build.gradle.kts \
    services/account-service/gradle.lockfile \
    services/risk-service/src/main services/risk-service/build.gradle.kts \
    services/risk-service/gradle.lockfile \
    services/persistence/src/main services/persistence/build.gradle.kts \
    services/persistence/gradle.lockfile \
    services/market-data-projection/src/main \
    services/market-data-projection/build.gradle.kts \
    services/market-data-projection/gradle.lockfile \
    services/marketdata-streamer/src/main \
    services/marketdata-streamer/build.gradle.kts \
    services/query-service/src/main services/query-service/build.gradle.kts \
    services/query-service/gradle.lockfile \
    services/quickfix-gateway/src/main \
    services/quickfix-gateway/build.gradle.kts \
    services/quickfix-gateway/gradle.lockfile \
    tools/market-reference-builder/data \
    deploy/docker deploy/compose deploy/k8s \
    ':(exclude)deploy/k8s/*.md' \
    ':(exclude)deploy/k8s/**/*.md' \
    CMakeLists.txt CMakePresets.json vcpkg.json triplets proto \
    matching-engine/include matching-engine/src \
    matching-engine/tests/matching_kafka_fixture_publisher.cpp \
    services/risk-service/src/main/java/com/simplematch/riskservice/cdc \
    services/risk-service/src/main/java/com/simplematch/riskservice/store \
    services/risk-service/src/main/java/com/simplematch/riskservice/config/RiskCdcDeliveryConfiguration.java \
    services/risk-service/src/main/java/com/simplematch/riskservice/config/CdcDeliveryProperties.java \
    services/risk-service/src/main/java/com/simplematch/riskservice/config/RiskServiceProperties.java \
    services/risk-service/src/main/resources/db/migration/risk-service/V10__record_cdc_delivery_observations.sql \
    services/risk-service/src/main/resources/db/migration/risk-service/V11__require_admission_artifact_route.sql \
    services/risk-service/src/main/resources/application.yaml \
    scripts/test-kubernetes-overlays.sh \
    scripts/test-local-kubernetes-dependencies.sh
}

simplematch_certification_scoped_source_signature() {
  local repo_root="$1"
  local scope="$2"
  local git_output manifest path digest declared_path executable
  shift 2
  (($# > 0)) || return 1

  for declared_path in "$@"; do
    [[ "$declared_path" == ':(exclude)'* ]] && continue
    git_output="$(git -C "$repo_root" ls-files -co --exclude-standard -- \
      "$declared_path")" || return 1
    [[ -n "$git_output" ]] || {
      printf 'certification %s provenance input is missing: %s\n' \
        "$scope" "$declared_path" >&2
      return 1
    }
  done

  git_output="$(git -C "$repo_root" ls-files -co --exclude-standard -- "$@" |
    LC_ALL=C sort -u)" || return 1
  [[ -n "$git_output" ]] || {
    printf 'certification %s provenance scope has no tracked inputs\n' "$scope" >&2
    return 1
  }
  manifest="$(
    printf 'scope\t%s\n' "$scope"
    while IFS= read -r path; do
      [[ -n "$path" ]] || continue
      [[ -f "$repo_root/$path" ]] || {
        printf 'certification %s provenance input is missing: %s\n' \
          "$scope" "$path" >&2
        exit 1
      }
      digest="$(sha256sum "$repo_root/$path" | awk '{print $1}')" || exit 1
      [[ "$digest" =~ ^[0-9a-f]{64}$ ]] || exit 1
      executable=false
      [[ -x "$repo_root/$path" ]] && executable=true
      printf 'file\t%s\t%s\texecutable=%s\n' \
        "$path" "$digest" "$executable"
    done <<<"$git_output"
  )" || return 1
  printf '%s\n' "$manifest" | sha256sum | awk '{print $1}'
}

simplematch_certification_cdc_runtime_signature() {
  local repo_root="$1"
  local path_output
  local -a paths=()
  path_output="$(simplematch_certification_cdc_runtime_source_paths)" || return 1
  mapfile -t paths <<<"$path_output"
  simplematch_certification_scoped_source_signature \
    "$repo_root" cdc-runtime "${paths[@]}"
}
