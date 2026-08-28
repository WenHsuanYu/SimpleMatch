#!/usr/bin/env bash

# Content-addressed PASS evidence for local production-like certification. The
# cache is an optimization and is never trusted without validating object
# content, phase identity, input identity, and declared outputs.

certification_cache_directory() {
  printf '%s\n' \
    "${SIMPLEMATCH_CERTIFICATION_CACHE_DIR:-$repo_root/out/certification-cache}"
}

_certification_evidence_validate_identity() {
  [[ "$1" =~ ^sha256:[0-9a-f]{64}$ ]]
}

_certification_evidence_object_path() {
  local evidence_digest="$1"
  local cache_dir hex

  _certification_evidence_validate_identity "$evidence_digest" || return 1
  cache_dir="$(certification_cache_directory)"
  hex="${evidence_digest#sha256:}"
  printf '%s/objects/sha256/%s/%s.json\n' \
    "$cache_dir" "${hex:0:2}" "${hex:2}"
}

_certification_evidence_index_path() {
  local phase_id="$1"
  local input_fingerprint="$2"
  local cache_dir fingerprint_hex

  certification_phase_policy "$phase_id" >/dev/null || return 1
  _certification_evidence_validate_identity "$input_fingerprint" || return 1
  cache_dir="$(certification_cache_directory)"
  fingerprint_hex="${input_fingerprint#sha256:}"
  printf '%s/index/v1/%s/%s.ref\n' \
    "$cache_dir" "$phase_id" "$fingerprint_hex"
}

_certification_evidence_file_digest() {
  local path="$1"
  local digest

  digest="$(sha256sum "$path" | awk '{print $1}')" || return 1
  [[ "$digest" =~ ^[0-9a-f]{64}$ ]] || return 1
  printf 'sha256:%s\n' "$digest"
}

certification_evidence_validate_object() {
  local object_path="$1"
  local expected_digest="$2"
  local expected_phase="${3:-}"
  local expected_input="${4:-}"
  local actual_digest phase_id input_fingerprint
  local definition_version current_definition_version

  [[ -f "$object_path" ]] || return 1
  _certification_evidence_validate_identity "$expected_digest" || return 1
  actual_digest="$(_certification_evidence_file_digest "$object_path")" || return 1
  [[ "$actual_digest" == "$expected_digest" ]] || return 1
  jq -e '
    .schemaVersion == 1 and
    (.phaseId | type == "string") and
    (.definitionVersion | type == "number") and
    (.definitionVersion >= 1) and
    .status == "PASS" and
    (.inputFingerprint | test("^sha256:[0-9a-f]{64}$")) and
    (.outputs | type == "array")
  ' "$object_path" >/dev/null 2>&1 || return 1

  phase_id="$(jq -r '.phaseId' "$object_path")" || return 1
  input_fingerprint="$(jq -r '.inputFingerprint' "$object_path")" || return 1
  definition_version="$(jq -r '.definitionVersion' "$object_path")" || return 1
  [[ -z "$expected_phase" || "$phase_id" == "$expected_phase" ]] || return 1
  [[ -z "$expected_input" || "$input_fingerprint" == "$expected_input" ]] || return 1
  current_definition_version="$(
    certification_phase_definition_version "$phase_id" 2>/dev/null
  )" || return 1
  [[ "$definition_version" == "$current_definition_version" ]]
}

certification_evidence_find_valid() {
  local phase_id="$1"
  local input_fingerprint="$2"
  local index_path evidence_digest object_path

  index_path="$(_certification_evidence_index_path "$phase_id" "$input_fingerprint")" || return 1
  [[ -f "$index_path" ]] || return 1
  evidence_digest="$(tr -d '\r\n' <"$index_path")"
  _certification_evidence_validate_identity "$evidence_digest" || return 1
  object_path="$(_certification_evidence_object_path "$evidence_digest")" || return 1
  certification_evidence_validate_object \
    "$object_path" "$evidence_digest" "$phase_id" "$input_fingerprint" || return 1
  printf '%s\n' "$evidence_digest"
}

_certification_evidence_install_object() {
  local object_temp="$1"
  local object_path="$2"
  local evidence_digest="$3"

  # A hard link provides create-if-absent semantics on the same filesystem. If
  # another process wins the race, validate its immutable object before using
  # it; never overwrite an existing content-addressed object.
  if ln -- "$object_temp" "$object_path" 2>/dev/null; then
    rm -f -- "$object_temp"
    return 0
  fi
  if [[ -f "$object_path" ]] && \
      certification_evidence_validate_object "$object_path" "$evidence_digest"; then
    rm -f -- "$object_temp"
    return 0
  fi
  rm -f -- "$object_temp"
  return 1
}

certification_evidence_publish() {
  local phase_id="$1"
  local input_fingerprint="$2"
  local result_file="$3"
  local cache_dir object_temp evidence_digest object_path index_path index_temp
  local definition_version

  certification_phase_policy "$phase_id" >/dev/null || return 1
  _certification_evidence_validate_identity "$input_fingerprint" || return 1
  [[ -f "$result_file" ]] || return 1
  definition_version="$(certification_phase_definition_version "$phase_id")" || return 1
  jq -e \
    --arg phase "$phase_id" \
    --arg input "$input_fingerprint" \
    --argjson definitionVersion "$definition_version" '
      .schemaVersion == 1 and
      .phaseId == $phase and
      .definitionVersion == $definitionVersion and
      .inputFingerprint == $input and
      .status == "PASS" and
      (.outputs | type == "array")
    ' "$result_file" >/dev/null 2>&1 || return 1

  cache_dir="$(certification_cache_directory)"
  mkdir -p "$cache_dir/objects/sha256" "$cache_dir/index/v1/$phase_id" || return 1
  object_temp="$(mktemp "$cache_dir/objects/.evidence.XXXXXX")" || return 1
  cp -- "$result_file" "$object_temp" || {
    rm -f -- "$object_temp"
    return 1
  }
  evidence_digest="$(_certification_evidence_file_digest "$object_temp")" || {
    rm -f -- "$object_temp"
    return 1
  }
  object_path="$(_certification_evidence_object_path "$evidence_digest")" || {
    rm -f -- "$object_temp"
    return 1
  }
  mkdir -p "$(dirname -- "$object_path")" || {
    rm -f -- "$object_temp"
    return 1
  }
  _certification_evidence_install_object \
    "$object_temp" "$object_path" "$evidence_digest" || return 1
  certification_evidence_validate_object \
    "$object_path" "$evidence_digest" "$phase_id" "$input_fingerprint" || return 1

  index_path="$(_certification_evidence_index_path "$phase_id" "$input_fingerprint")" || return 1
  mkdir -p "$(dirname -- "$index_path")" || return 1
  index_temp="$(mktemp "${index_path}.tmp.XXXXXX")" || return 1
  printf '%s\n' "$evidence_digest" >"$index_temp" || {
    rm -f -- "$index_temp"
    return 1
  }
  mv -f -- "$index_temp" "$index_path" || {
    rm -f -- "$index_temp"
    return 1
  }
  printf '%s\n' "$evidence_digest"
}

certification_evidence_materialize() {
  local evidence_digest="$1"
  local destination="$2"
  local object_path temp_path

  object_path="$(_certification_evidence_object_path "$evidence_digest")" || return 1
  certification_evidence_validate_object "$object_path" "$evidence_digest" || return 1
  mkdir -p "$(dirname -- "$destination")" || return 1
  temp_path="$(mktemp "${destination}.tmp.XXXXXX")" || return 1
  cp -- "$object_path" "$temp_path" || {
    rm -f -- "$temp_path"
    return 1
  }
  mv -f -- "$temp_path" "$destination" || {
    rm -f -- "$temp_path"
    return 1
  }
}

certification_evidence_output_identity() {
  local evidence_digest="$1"
  local kind="$2"
  local name="$3"
  local object_path identity

  object_path="$(_certification_evidence_object_path "$evidence_digest")" || return 1
  certification_evidence_validate_object "$object_path" "$evidence_digest" || return 1
  identity="$(
    jq -er --arg kind "$kind" --arg name "$name" '
      [.outputs[] | select(.kind == $kind and .name == $name)] as $matches |
      if ($matches | length) == 1 then $matches[0].identity else error("output mismatch") end
    ' "$object_path"
  )" || return 1
  _certification_evidence_validate_identity "$identity" || return 1
  printf '%s\n' "$identity"
}

certification_evidence_output_location() {
  local evidence_digest="$1"
  local kind="$2"
  local name="$3"
  local object_path location

  object_path="$(_certification_evidence_object_path "$evidence_digest")" || return 1
  certification_evidence_validate_object "$object_path" "$evidence_digest" || return 1
  location="$(
    jq -er --arg kind "$kind" --arg name "$name" '
      [.outputs[] | select(.kind == $kind and .name == $name)] as $matches |
      if ($matches | length) == 1 then ($matches[0].location // "") else error("output mismatch") end
    ' "$object_path"
  )" || return 1
  [[ -n "$location" && "$location" != *[[:space:]]* ]] || return 1
  printf '%s\n' "$location"
}
