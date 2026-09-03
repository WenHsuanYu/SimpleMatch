#!/usr/bin/env bash

# Deterministic effective-input manifests and fingerprints for local
# certification phases. This module calculates identity only; it does not
# execute phases or mutate reusable evidence.

_fingerprint_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/lib/local-image-inventory.sh
source "$_fingerprint_dir/local-image-inventory.sh"
unset _fingerprint_dir

SIMPLEMATCH_BOOT_BUILDER_IMAGE_DEFAULT="paketobuildpacks/builder-noble-java-tiny:latest"
# Retained for compatibility with focused contracts that explicitly clear the
# previous implementation caches. Canonical manifests are now the source of
# truth and these values are not used for correctness.
_certification_spring_common_fingerprint=""
_certification_spring_toolchain_fingerprint=""
_certification_docker_context_fingerprint=""

certification_sha256_stream() {
  local digest
  digest="$(sha256sum | awk '{print $1}')" || return 1
  [[ "$digest" =~ ^[0-9a-f]{64}$ ]] || return 1
  printf 'sha256:%s\n' "$digest"
}

_certification_emit_file_identity() {
  local relative_path="$1"
  local absolute_path="$repo_root/$relative_path"
  local executable=false
  local digest target

  [[ -e "$absolute_path" || -L "$absolute_path" ]] || {
    printf 'certification fingerprint input is missing: %s\n' "$relative_path" >&2
    return 1
  }
  if [[ -L "$absolute_path" ]]; then
    target="$(readlink -- "$absolute_path")" || return 1
    printf 'symlink\t%s\t%s\n' "$relative_path" "$target"
    return 0
  fi
  [[ -f "$absolute_path" ]] || {
    printf 'certification fingerprint input is not a file: %s\n' "$relative_path" >&2
    return 1
  }
  [[ -x "$absolute_path" ]] && executable=true
  digest="$(sha256sum "$absolute_path" | awk '{print $1}')" || return 1
  printf 'file\t%s\t%s\texecutable=%s\n' \
    "$relative_path" "$digest" "$executable"
}

_certification_list_git_inputs() {
  local git_output

  git_output="$(
    git -C "$repo_root" ls-files -co --exclude-standard -- "$@"
  )" || return 1
  printf '%s\n' "$git_output" | LC_ALL=C sort -u
}

certification_fingerprint_paths() {
  local relative_path input_output
  local -a inputs=("$@")

  ((${#inputs[@]} > 0)) || {
    printf '%s\n' 'certification fingerprint requires at least one input path' >&2
    return 1
  }
  input_output="$(_certification_list_git_inputs "${inputs[@]}")" || return 1
  while IFS= read -r relative_path; do
    [[ -n "$relative_path" ]] || continue
    _certification_emit_file_identity "$relative_path" || return 1
  done <<<"$input_output"
}

_certification_paths_and_values_manifest() {
  local in_values=false
  local argument value_index=0
  local -a paths=() values=()

  for argument in "$@"; do
    if [[ "$argument" == -- ]]; then
      in_values=true
    elif [[ "$in_values" == false ]]; then
      paths+=("$argument")
    else
      values+=("$argument")
    fi
  done

  if ((${#paths[@]} > 0)); then
    certification_fingerprint_paths "${paths[@]}" || return 1
  fi
  for argument in "${values[@]}"; do
    printf 'value\t%06d\t%s\n' "$value_index" "$argument"
    value_index=$((value_index + 1))
  done
}

_certification_local_image_identity() {
  local image_reference="$1"
  local identity

  command -v docker >/dev/null 2>&1 || return 1
  identity="$(docker image inspect --format '{{.Id}}' "$image_reference" 2>/dev/null)" || \
    return 1
  [[ "$identity" =~ ^sha256:[0-9a-f]{64}$ ]] || return 1
  printf '%s\n' "$identity"
}

_certification_remote_image_identity() {
  local image_reference="$1"
  local identity

  command -v docker >/dev/null 2>&1 || return 1
  docker buildx version >/dev/null 2>&1 || return 1
  identity="$(
    docker buildx imagetools inspect "$image_reference" 2>/dev/null |
      sed -nE 's/^Digest:[[:space:]]+(sha256:[0-9a-f]{64})$/\1/p' |
      head -1
  )" || return 1
  [[ "$identity" =~ ^sha256:[0-9a-f]{64}$ ]] || return 1
  printf '%s\n' "$identity"
}

certification_resolve_image_identity() {
  local image_reference="$1"
  local pull_policy="${2:-IF_NOT_PRESENT}"
  local identity

  case "$pull_policy" in
    ALWAYS)
      _certification_remote_image_identity "$image_reference"
      ;;
    IF_NOT_PRESENT)
      if identity="$(_certification_local_image_identity "$image_reference" 2>/dev/null)"; then
        printf '%s\n' "$identity"
      else
        _certification_remote_image_identity "$image_reference"
      fi
      ;;
    NEVER)
      _certification_local_image_identity "$image_reference"
      ;;
    *)
      printf 'unsupported image pull policy for certification fingerprint: %s\n' \
        "$pull_policy" >&2
      return 1
      ;;
  esac
}

_certification_effective_boot_pull_policy() {
  if [[ -n "${SIMPLEMATCH_BOOT_PULL_POLICY:-}" ]]; then
    case "$SIMPLEMATCH_BOOT_PULL_POLICY" in
      ALWAYS|IF_NOT_PRESENT|NEVER)
        printf '%s\n' "$SIMPLEMATCH_BOOT_PULL_POLICY"
        return 0
        ;;
      *)
        printf 'unsupported SIMPLEMATCH_BOOT_PULL_POLICY: %s\n' \
          "$SIMPLEMATCH_BOOT_PULL_POLICY" >&2
        return 1
        ;;
    esac
  fi

  if [[ -n "${SIMPLEMATCH_BOOT_RUN_IMAGE:-}" ]]; then
    printf '%s\n' IF_NOT_PRESENT
  else
    # BootBuildImage defaults to ALWAYS when the build does not override the
    # pull policy, so mutable builder/run-image tags must be resolved remotely.
    printf '%s\n' ALWAYS
  fi
}

_certification_remote_image_config() {
  local image_reference="$1"
  local image_json

  docker buildx version >/dev/null 2>&1 || return 1
  image_json="$(
    docker buildx imagetools inspect "$image_reference" \
      --format '{{json (index .Image "linux/amd64")}}' 2>/dev/null
  )" || image_json="$(
    docker buildx imagetools inspect "$image_reference" \
      --format '{{json .Image}}' 2>/dev/null
  )" || return 1
  jq -e 'type == "object" and (.config | type == "object")' \
    <<<"$image_json" >/dev/null 2>&1 || return 1
  printf '%s\n' "$image_json"
}

_certification_boot_builder_metadata() {
  local builder_reference="$1"
  local pull_policy="$2"
  local metadata image_json local_identity

  if [[ "$pull_policy" != ALWAYS ]]; then
    if local_identity="$(_certification_local_image_identity "$builder_reference" 2>/dev/null)"; then
      metadata="$(
        docker image inspect --format \
          '{{ index .Config.Labels "io.buildpacks.builder.metadata" }}' \
          "$builder_reference" 2>/dev/null
      )" || return 1
      [[ -n "$metadata" && "$metadata" != '<no value>' ]] || return 1
      printf '%s\n' "$metadata"
      return 0
    fi
    [[ "$pull_policy" != NEVER ]] || return 1
  fi

  image_json="$(_certification_remote_image_config "$builder_reference")" || return 1
  metadata="$(
    jq -er '.config.Labels["io.buildpacks.builder.metadata"] // empty' \
      <<<"$image_json"
  )" || return 1
  [[ -n "$metadata" ]] || return 1
  printf '%s\n' "$metadata"
}

_certification_boot_run_image_reference() {
  local builder_reference="$1"
  local pull_policy="$2"
  local metadata run_image

  if [[ -n "${SIMPLEMATCH_BOOT_RUN_IMAGE:-}" ]]; then
    printf '%s\n' "$SIMPLEMATCH_BOOT_RUN_IMAGE"
    return 0
  fi

  metadata="$(_certification_boot_builder_metadata \
    "$builder_reference" "$pull_policy")" || return 1
  run_image="$(
    jq -r '
      .images[0].image //
      .run.images[0].image //
      .runImage.image //
      .stack.runImage.image //
      empty
    ' <<<"$metadata"
  )" || return 1
  [[ -n "$run_image" && "$run_image" != null ]] || return 1
  printf '%s\n' "$run_image"
}

_certification_spring_toolchain_identity() {
  local builder_reference builder_identity run_image_reference run_image_identity
  local pull_policy

  pull_policy="$(_certification_effective_boot_pull_policy)" || return 1
  builder_reference="${SIMPLEMATCH_BOOT_BUILDER_IMAGE:-$SIMPLEMATCH_BOOT_BUILDER_IMAGE_DEFAULT}"
  builder_identity="$(certification_resolve_image_identity \
    "$builder_reference" "$pull_policy")" || return 1
  run_image_reference="$(_certification_boot_run_image_reference \
    "$builder_reference" "$pull_policy")" || return 1
  run_image_identity="$(certification_resolve_image_identity \
    "$run_image_reference" "$pull_policy")" || return 1

  printf 'builder\t%s@%s\n' "$builder_reference" "$builder_identity"
  printf 'runImage\t%s@%s\n' "$run_image_reference" "$run_image_identity"
  printf 'pullPolicy\t%s\n' "$pull_policy"
}

_certification_repository_docker_context_identity() {
  certification_fingerprint_paths . | certification_sha256_stream
}

_certification_dockerfile_base_identities() {
  local dockerfile="$1"
  local reference identity reference_output

  reference_output="$(
    awk 'toupper($1) == "FROM" { print $2 }' "$repo_root/$dockerfile"
  )" || return 1
  reference_output="$(printf '%s\n' "$reference_output" | LC_ALL=C sort -u)" || return 1

  while IFS= read -r reference; do
    [[ -n "$reference" ]] || continue
    [[ "$reference" != *'$'* ]] || {
      printf 'cannot resolve variable Docker base image in %s: %s\n' \
        "$dockerfile" "$reference" >&2
      return 1
    }
    identity="$(certification_resolve_image_identity "$reference" ALWAYS)" || {
      printf 'cannot resolve immutable Docker base image for %s: %s\n' \
        "$dockerfile" "$reference" >&2
      return 1
    }
    printf 'baseImage\t%s@%s\n' "$reference" "$identity"
  done <<<"$reference_output"
}

certification_image_input_manifest() {
  local service="$1"
  local entry image_class build_source repository phase_version context_identity

  entry="$(simplematch_local_image_inventory_entry "$service")" || return 1
  IFS='|' read -r image_class _ build_source repository <<<"$entry"
  phase_version="$(certification_phase_definition_version \
    "local-image-build/$service")" || return 1

  printf 'phase\tlocal-image-build/%s\n' "$service"
  printf 'version\t%s\n' "$phase_version"
  case "$image_class" in
    spring)
      certification_fingerprint_paths \
        build-logic shared-java proto gradle \
        settings.gradle.kts build.gradle.kts gradlew gradlew.bat \
        scripts/build-local-images.sh scripts/lib/local-image-inventory.sh \
        scripts/lib/local-certification-images.sh \
        scripts/lib/local-certification-artifacts.sh \
        scripts/lib/local-certification-fingerprint.sh || return 1
      certification_fingerprint_paths "services/$service" || return 1
      _certification_spring_toolchain_identity || return 1
      printf 'repository\t%s\n' "$repository"
      ;;
    flyway|verification|native)
      context_identity="$(_certification_repository_docker_context_identity)" || return 1
      printf 'dockerContext\t%s\n' "$context_identity"
      certification_fingerprint_paths \
        "$build_source" scripts/build-local-images.sh \
        scripts/lib/local-image-inventory.sh \
        scripts/lib/local-certification-images.sh \
        scripts/lib/local-certification-artifacts.sh \
        scripts/lib/local-certification-fingerprint.sh || return 1
      _certification_dockerfile_base_identities "$build_source" || return 1
      printf 'repository\t%s\n' "$repository"
      [[ "$image_class" != native ]] || printf 'vcpkg\t2026.07.29\n'
      ;;
    *)
      printf 'unsupported image class for fingerprinting: %s\n' "$image_class" >&2
      return 1
      ;;
  esac
}

certification_image_input_fingerprint() {
  local service="$1"
  certification_image_input_manifest "$service" | certification_sha256_stream
}

_certification_registry_publish_manifest() {
  local phase_id="$1"
  local phase_version="$2"
  local service source_image source_identity endpoint

  service="${phase_id#registry-publish/}"
  source_image="$(simplematch_local_image_inventory_source_image \
    "$service" "$image_tag")" || return 1
  source_identity="$(certification_source_image_identity "$service")" || return 1
  endpoint="$(simplematch_registry_endpoint)" || return 1

  certification_fingerprint_paths \
    scripts/publish-local-images.sh \
    scripts/lib/local-registry.sh \
    scripts/lib/local-image-inventory.sh \
    scripts/lib/local-image-transport.sh \
    scripts/lib/local-certification-images.sh \
    scripts/lib/local-certification-artifacts.sh \
    scripts/lib/local-certification-fingerprint.sh || return 1
  printf 'phase\t%s\n' "$phase_id"
  printf 'version\t%s\n' "$phase_version"
  printf 'service\t%s\n' "$service"
  printf 'sourceImage\t%s\n' "$source_image"
  printf 'sourceIdentity\t%s\n' "$source_identity"
  printf 'registry\t%s\n' "$endpoint"
}

_certification_registry_lock_manifest() {
  local phase_version="$1"
  local service fragment_file entry selected_services

  [[ -n "${registry_fragment_directory:-}" ]] || return 1
  selected_services="$(certification_selected_image_services)" || return 1
  certification_fingerprint_paths \
    scripts/lib/local-image-inventory.sh \
    scripts/lib/local-image-transport.sh \
    scripts/lib/local-certification-images.sh \
    scripts/lib/local-certification-artifacts.sh \
    scripts/lib/local-certification-fingerprint.sh || return 1
  printf 'phase\tregistry-image-lock\n'
  printf 'version\t%s\n' "$phase_version"
  while IFS= read -r service; do
    [[ -n "$service" ]] || continue
    fragment_file="$registry_fragment_directory/${service}.lock"
    entry="$(simplematch_local_image_lock_entry "$fragment_file" "$service")" || return 1
    printf 'entry\t%s\n' "$entry"
  done <<<"$selected_services"
}

_certification_fixture_validator_identity_manifest() {
  local configured_path absolute_path digest=missing

  configured_path="${SIMPLEMATCH_MATCHING_FIXTURE_PUBLISHER_BIN:-$repo_root/out/build/full-native-dev/simplematch-matching-kafka-fixture-publisher}"
  absolute_path="$configured_path"
  if [[ "$absolute_path" != /* ]]; then
    absolute_path="$repo_root/$absolute_path"
  fi
  if [[ -f "$absolute_path" ]]; then
    digest="$(sha256sum "$absolute_path" | awk '{print $1}')" || digest=invalid
  fi
  printf 'validatorPath\t%s\nvalidatorSha256\t%s\n' \
    "$configured_path" "$digest"
}

certification_phase_input_manifest() {
  local phase_id="$1"
  shift
  local phase_version service value_index=0 value

  phase_version="$(certification_phase_definition_version "$phase_id")" || return 1
  case "$phase_id" in
    static-kubernetes-overlays)
      _certification_paths_and_values_manifest \
        scripts/test-kubernetes-overlays.sh deploy/k8s \
        scripts/lib/local-certification-fingerprint.sh \
        -- "phase=$phase_id" "version=$phase_version"
      ;;
    static-phase1-deployment-contracts)
      _certification_paths_and_values_manifest \
        scripts/test-phase1-deployment-contracts.sh \
        scripts/verify-outbox-connector-contracts.sh \
        scripts/test-local-resilience.sh scripts/validate-local-resilience-contract.sh \
        scripts/lib/local-resilience.sh deploy/compose/*-outbox-connector.json \
        services/risk-service/src/main/java services/account-service/src/main/java \
        scripts/lib/local-certification-fingerprint.sh \
        -- "phase=$phase_id" "version=$phase_version"
      ;;
    static-kubernetes-dependencies)
      _certification_paths_and_values_manifest \
        scripts/test-local-kubernetes-dependencies.sh deploy/k8s \
        scripts/lib/local-image-inventory.sh scripts/lib/local-image-transport.sh \
        scripts/lib/local-certification-fingerprint.sh \
        -- "phase=$phase_id" "version=$phase_version"
      ;;
    static-matching-manifests)
      _certification_paths_and_values_manifest \
        scripts/test-matching-kubernetes-manifests.sh deploy/k8s \
        scripts/lib/local-certification-fingerprint.sh \
        -- "phase=$phase_id" "version=$phase_version"
      ;;
    static-matching-profile)
      _certification_paths_and_values_manifest \
        scripts/test-matching-topic-profile.sh scripts/validate-matching-topic-profile.sh \
        scripts/lib/matching-topic-profile.sh scripts/testdata/matching-topic-profile \
        scripts/lib/local-certification-fingerprint.sh \
        -- "phase=$phase_id" "version=$phase_version"
      ;;
    static-flyway-services)
      _certification_paths_and_values_manifest \
        scripts/test-flyway-services.sh build-logic services \
        scripts/lib/local-certification-fingerprint.sh \
        -- "phase=$phase_id" "version=$phase_version"
      ;;
    compose-config)
      _certification_paths_and_values_manifest \
        deploy/compose/kafka-connect.production-like.yml \
        scripts/lib/local-certification-fingerprint.sh \
        -- "phase=$phase_id" "version=$phase_version" \
        "postgresPassword=${local_postgres_password:-}" \
        "network=${SIMPLEMATCH_PRODUCTION_LIKE_NETWORK:-kind}" \
        "networkExternal=${SIMPLEMATCH_PRODUCTION_LIKE_NETWORK_EXTERNAL:-true}"
      ;;
    cdc-outbox-failure-live)
      _certification_paths_and_values_manifest \
        scripts/run-outbox-cdc-contract-check.sh scripts/lib/cdc-verifier.sh \
        deploy/compose/apply-risk-service-outbox-connector.sh \
        deploy/compose/apply-account-service-outbox-connector.sh \
        deploy/compose/apply-marketdata-publisher-outbox-connector.sh \
        deploy/compose/apply-outbox-connector.sh \
        deploy/compose/risk-service-outbox-connector.json \
        deploy/compose/account-service-outbox-connector.json \
        deploy/compose/marketdata-publisher-outbox-connector.json \
        deploy/compose/kafka-connect.local.yml \
        scripts/lib/local-certification-fingerprint.sh \
        -- "phase=$phase_id" "version=$phase_version"
      ;;
    kubernetes-cdc-delivery)
      _certification_paths_and_values_manifest \
        scripts/run-risk-cdc-delivery-observer-check.sh \
        scripts/lib/local-resilience.sh scripts/lib/local-certification-connect.sh \
        scripts/lib/local-certification-run.sh scripts/lib/local-certification-kubernetes.sh \
        scripts/lib/local-kind.sh \
        CMakeLists.txt CMakePresets.json vcpkg.json triplets proto \
        matching-engine/include matching-engine/src \
        matching-engine/tests/matching_kafka_fixture_publisher.cpp \
        services/risk-service/src/main/java/com/simplematch/riskservice/cdc \
        services/risk-service/src/main/java/com/simplematch/riskservice/store \
        services/risk-service/src/main/java/com/simplematch/riskservice/config/RiskCdcDeliveryConfiguration.java \
        services/risk-service/src/main/java/com/simplematch/riskservice/config/CdcDeliveryProperties.java \
        services/risk-service/src/main/java/com/simplematch/riskservice/config/RiskServiceProperties.java \
        services/risk-service/src/main/resources/db/migration/risk-service/V10__record_cdc_delivery_observations.sql \
        services/risk-service/src/main/resources/application.yaml \
        deploy/k8s/simplematch-platform-configmap.yaml deploy/k8s/risk-service-configmap.yaml \
        deploy/k8s/overlays/local/local-runtime-patch.yaml \
        deploy/k8s/overlays/local/kafka-connect-network-policy.yaml \
        deploy/k8s/debezium-kafka-connect-local.yaml \
        deploy/k8s/risk-service-outbox-connector-configmap.yaml \
        deploy/k8s/account-service-outbox-connector-configmap.yaml \
        deploy/k8s/marketdata-publisher-outbox-connector-configmap.yaml \
        scripts/test-kubernetes-overlays.sh scripts/test-local-kubernetes-dependencies.sh \
        scripts/lib/local-certification-artifacts.sh \
        scripts/lib/local-certification-fingerprint.sh \
        -- "phase=$phase_id" "version=$phase_version"
      _certification_fixture_validator_identity_manifest
      ;;
    local-image-inventory)
      _certification_paths_and_values_manifest \
        scripts/build-local-images.sh scripts/lib/local-image-inventory.sh \
        scripts/lib/local-certification-fingerprint.sh \
        -- "phase=$phase_id" "version=$phase_version"
      ;;
    kafka-producer-contract)
      _certification_paths_and_values_manifest \
        scripts/validate-matching-producer-contract.sh \
        scripts/lib/matching-topic-profile.sh \
        scripts/lib/local-certification-kafka.sh \
        scripts/lib/local-certification-artifacts.sh \
        scripts/testdata/matching-topic-profile config/kafka \
        scripts/lib/local-certification-fingerprint.sh \
        -- "phase=$phase_id" "version=$phase_version"
      ;;
    local-image-build/*)
      service="${phase_id#local-image-build/}"
      certification_image_input_manifest "$service"
      ;;
    registry-publish/*)
      _certification_registry_publish_manifest "$phase_id" "$phase_version"
      ;;
    registry-image-lock)
      _certification_registry_lock_manifest "$phase_version"
      ;;
    *)
      printf 'phase\t%s\n' "$phase_id"
      printf 'version\t%s\n' "$phase_version"
      printf 'inputKinds\t%s\n' "$(certification_phase_input_kinds "$phase_id")"
      printf 'outputKinds\t%s\n' "$(certification_phase_output_kinds "$phase_id")"
      printf 'run\t%s\n' "${run_id:-unknown}"
      printf 'source\t%s\n' "${source_signature:-unknown}"
      printf 'tradingDay\t%s\n' "${certification_trading_day:-unknown}"
      for value in "$@"; do
        printf 'input\t%06d\t%s\n' "$value_index" "$value"
        value_index=$((value_index + 1))
      done
      ;;
  esac
}

certification_phase_fingerprint() {
  local phase_id="$1"
  shift
  certification_phase_input_manifest "$phase_id" "$@" | certification_sha256_stream
}
