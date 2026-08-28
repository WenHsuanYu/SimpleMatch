#!/usr/bin/env bash

# Deterministic effective-input fingerprints for local certification phases.
# This module calculates identity only; it does not execute phases or mutate
# reusable evidence.

_fingerprint_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/lib/local-image-inventory.sh
source "$_fingerprint_dir/local-image-inventory.sh"
unset _fingerprint_dir

SIMPLEMATCH_BOOT_BUILDER_IMAGE_DEFAULT="paketobuildpacks/builder-noble-java-tiny:latest"
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

_certification_hash_paths_and_values() {
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

  {
    if ((${#paths[@]} > 0)); then
      certification_fingerprint_paths "${paths[@]}" || exit 1
    fi
    for argument in "${values[@]}"; do
      printf 'value\t%06d\t%s\n' "$value_index" "$argument"
      value_index=$((value_index + 1))
    done
  } | certification_sha256_stream
}

certification_resolve_image_identity() {
  local image_reference="$1"
  local identity

  command -v docker >/dev/null 2>&1 || return 1
  identity="$(docker image inspect --format '{{.Id}}' "$image_reference" 2>/dev/null || true)"
  if [[ "$identity" =~ ^sha256:[0-9a-f]{64}$ ]]; then
    printf '%s\n' "$identity"
    return 0
  fi

  docker buildx version >/dev/null 2>&1 || return 1
  identity="$(
    docker buildx imagetools inspect "$image_reference" 2>/dev/null |
      sed -nE 's/^Digest:[[:space:]]+(sha256:[0-9a-f]{64})$/\1/p' |
      head -1
  )"
  [[ "$identity" =~ ^sha256:[0-9a-f]{64}$ ]] || return 1
  printf '%s\n' "$identity"
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
  local metadata image_json

  metadata="$(
    docker image inspect --format \
      '{{ index .Config.Labels "io.buildpacks.builder.metadata" }}' \
      "$builder_reference" 2>/dev/null || true
  )"
  if [[ -n "$metadata" && "$metadata" != '<no value>' ]]; then
    printf '%s\n' "$metadata"
    return 0
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
  local metadata run_image

  if [[ -n "${SIMPLEMATCH_BOOT_RUN_IMAGE:-}" ]]; then
    printf '%s\n' "$SIMPLEMATCH_BOOT_RUN_IMAGE"
    return 0
  fi

  metadata="$(_certification_boot_builder_metadata "$builder_reference")" || return 1
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

  builder_reference="${SIMPLEMATCH_BOOT_BUILDER_IMAGE:-$SIMPLEMATCH_BOOT_BUILDER_IMAGE_DEFAULT}"
  builder_identity="$(certification_resolve_image_identity "$builder_reference")" || return 1
  run_image_reference="$(_certification_boot_run_image_reference "$builder_reference")" || return 1
  run_image_identity="$(certification_resolve_image_identity "$run_image_reference")" || return 1

  printf 'builder=%s@%s\n' "$builder_reference" "$builder_identity"
  printf 'runImage=%s@%s\n' "$run_image_reference" "$run_image_identity"
  printf 'pullPolicy=%s\n' "${SIMPLEMATCH_BOOT_PULL_POLICY:-DEFAULT}"
}

_certification_spring_toolchain_fingerprint_value() {
  if [[ -n "$_certification_spring_toolchain_fingerprint" ]]; then
    printf '%s\n' "$_certification_spring_toolchain_fingerprint"
    return 0
  fi
  _certification_spring_toolchain_fingerprint="$(
    _certification_spring_toolchain_identity | certification_sha256_stream
  )" || return 1
  printf '%s\n' "$_certification_spring_toolchain_fingerprint"
}

_certification_spring_common_identity() {
  if [[ -n "$_certification_spring_common_fingerprint" ]]; then
    printf '%s\n' "$_certification_spring_common_fingerprint"
    return 0
  fi
  _certification_spring_common_fingerprint="$(
    _certification_hash_paths_and_values \
      build-logic shared-java proto gradle \
      settings.gradle.kts build.gradle.kts gradlew gradlew.bat \
      scripts/build-local-images.sh scripts/lib/local-image-inventory.sh \
      -- 'springBoot=4.1.0' 'gradle=9.7.0'
  )" || return 1
  printf '%s\n' "$_certification_spring_common_fingerprint"
}

_certification_repository_docker_context_identity() {
  if [[ -n "$_certification_docker_context_fingerprint" ]]; then
    printf '%s\n' "$_certification_docker_context_fingerprint"
    return 0
  fi
  _certification_docker_context_fingerprint="$(
    {
      certification_fingerprint_paths . || exit 1
      printf 'dockerignore\t'
      sha256sum "$repo_root/.dockerignore" | awk '{print $1}'
    } | certification_sha256_stream
  )" || return 1
  printf '%s\n' "$_certification_docker_context_fingerprint"
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
    identity="$(certification_resolve_image_identity "$reference")" || {
      printf 'cannot resolve immutable Docker base image for %s: %s\n' \
        "$dockerfile" "$reference" >&2
      return 1
    }
    printf '%s@%s\n' "$reference" "$identity"
  done <<<"$reference_output"
}

certification_image_input_fingerprint() {
  local service="$1"
  local entry image_class build_source repository
  local common_identity context_identity toolchain_identity

  entry="$(simplematch_local_image_inventory_entry "$service")" || return 1
  IFS='|' read -r image_class _ build_source repository <<<"$entry"

  case "$image_class" in
    spring)
      common_identity="$(_certification_spring_common_identity)" || return 1
      toolchain_identity="$(_certification_spring_toolchain_fingerprint_value)" || return 1
      {
        printf 'phase\tlocal-image-build/%s\n' "$service"
        printf 'common\t%s\n' "$common_identity"
        printf 'toolchain\t%s\n' "$toolchain_identity"
        certification_fingerprint_paths "services/$service" || exit 1
        printf 'repository\t%s\n' "$repository"
      } | certification_sha256_stream
      ;;
    flyway|verification|native)
      context_identity="$(_certification_repository_docker_context_identity)" || return 1
      {
        printf 'phase\tlocal-image-build/%s\n' "$service"
        printf 'context\t%s\n' "$context_identity"
        _certification_dockerfile_base_identities "$build_source" || exit 1
        printf 'repository\t%s\n' "$repository"
        [[ "$image_class" != native ]] || printf 'vcpkg\t2026.07.29\n'
      } | certification_sha256_stream
      ;;
    *)
      printf 'unsupported image class for fingerprinting: %s\n' "$image_class" >&2
      return 1
      ;;
  esac
}

_certification_registry_publish_fingerprint() {
  local phase_id="$1"
  local phase_version="$2"
  local service source_image source_identity endpoint

  service="${phase_id#registry-publish/}"
  source_image="$(simplematch_local_image_inventory_source_image "$service" "$image_tag")" || return 1
  source_identity="$(certification_source_image_identity "$service")" || return 1
  endpoint="$(simplematch_registry_endpoint)" || return 1
  {
    printf 'phase\t%s\n' "$phase_id"
    printf 'version\t%s\n' "$phase_version"
    printf 'service\t%s\n' "$service"
    printf 'sourceImage\t%s\n' "$source_image"
    printf 'sourceIdentity\t%s\n' "$source_identity"
    printf 'registry\t%s\n' "$endpoint"
  } | certification_sha256_stream
}

_certification_registry_lock_fingerprint() {
  local phase_version="$1"
  local service fragment_file entry selected_services

  [[ -n "${registry_fragment_directory:-}" ]] || return 1
  selected_services="$(certification_selected_image_services)" || return 1
  {
    printf 'phase\tregistry-image-lock\n'
    printf 'version\t%s\n' "$phase_version"
    while IFS= read -r service; do
      [[ -n "$service" ]] || continue
      fragment_file="$registry_fragment_directory/${service}.lock"
      entry="$(simplematch_local_image_lock_entry "$fragment_file" "$service")" || exit 1
      printf 'entry\t%s\n' "$entry"
    done <<<"$selected_services"
  } | certification_sha256_stream
}

certification_phase_fingerprint() {
  local phase_id="$1"
  shift
  local phase_version service value_index=0 value

  phase_version="$(certification_phase_definition_version "$phase_id")" || return 1
  case "$phase_id" in
    static-kubernetes-overlays)
      _certification_hash_paths_and_values \
        scripts/test-kubernetes-overlays.sh deploy/k8s \
        scripts/lib/local-certification-fingerprint.sh \
        -- "phase=$phase_id" "version=$phase_version"
      ;;
    static-kubernetes-dependencies)
      _certification_hash_paths_and_values \
        scripts/test-local-kubernetes-dependencies.sh deploy/k8s \
        scripts/lib/local-image-inventory.sh scripts/lib/local-image-transport.sh \
        scripts/lib/local-certification-fingerprint.sh \
        -- "phase=$phase_id" "version=$phase_version"
      ;;
    static-matching-manifests)
      _certification_hash_paths_and_values \
        scripts/test-matching-kubernetes-manifests.sh deploy/k8s \
        scripts/lib/local-certification-fingerprint.sh \
        -- "phase=$phase_id" "version=$phase_version"
      ;;
    static-matching-profile)
      _certification_hash_paths_and_values \
        scripts/test-matching-topic-profile.sh scripts/validate-matching-topic-profile.sh \
        scripts/lib/matching-topic-profile.sh scripts/testdata/matching-topic-profile \
        scripts/lib/local-certification-fingerprint.sh \
        -- "phase=$phase_id" "version=$phase_version"
      ;;
    static-flyway-services)
      _certification_hash_paths_and_values \
        scripts/test-flyway-services.sh build-logic services \
        scripts/lib/local-certification-fingerprint.sh \
        -- "phase=$phase_id" "version=$phase_version"
      ;;
    compose-config)
      _certification_hash_paths_and_values \
        deploy/compose/kafka-connect.production-like.yml \
        scripts/lib/local-certification-fingerprint.sh \
        -- "phase=$phase_id" "version=$phase_version" \
        "postgresPassword=${local_postgres_password:-}" \
        "network=${SIMPLEMATCH_PRODUCTION_LIKE_NETWORK:-kind}" \
        "networkExternal=${SIMPLEMATCH_PRODUCTION_LIKE_NETWORK_EXTERNAL:-true}"
      ;;
    local-image-inventory)
      _certification_hash_paths_and_values \
        scripts/build-local-images.sh scripts/lib/local-image-inventory.sh \
        scripts/lib/local-certification-fingerprint.sh \
        -- "phase=$phase_id" "version=$phase_version"
      ;;
    kafka-producer-contract)
      _certification_hash_paths_and_values \
        scripts/validate-matching-producer-contract.sh scripts/lib/matching-topic-profile.sh \
        scripts/testdata/matching-topic-profile config/kafka \
        scripts/lib/local-certification-fingerprint.sh \
        -- "phase=$phase_id" "version=$phase_version"
      ;;
    local-image-build/*)
      service="${phase_id#local-image-build/}"
      certification_image_input_fingerprint "$service"
      ;;
    registry-publish/*)
      _certification_registry_publish_fingerprint "$phase_id" "$phase_version"
      ;;
    registry-image-lock)
      _certification_registry_lock_fingerprint "$phase_version"
      ;;
    *)
      # Fresh runtime phases receive a run-specific identity for current-run
      # evidence. Command arguments are audit inputs only here because these
      # phases are never eligible for cross-run reuse.
      {
        printf 'phase\t%s\n' "$phase_id"
        printf 'version\t%s\n' "$phase_version"
        printf 'run\t%s\n' "${run_id:-unknown}"
        printf 'source\t%s\n' "${source_signature:-unknown}"
        printf 'tradingDay\t%s\n' "${certification_trading_day:-unknown}"
        for value in "$@"; do
          printf 'input\t%06d\t%s\n' "$value_index" "$value"
          value_index=$((value_index + 1))
        done
      } | certification_sha256_stream
      ;;
  esac
}
