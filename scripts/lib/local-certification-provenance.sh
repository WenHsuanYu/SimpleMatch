#!/usr/bin/env bash

# Provenance helpers shared by the production-like runner and dependent
# certification workflows. A retained namespace is valid evidence only when the
# verifier process and repository revision come from the same completed run.

_provenance_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/lib/local-image-transport.sh
source "$_provenance_dir/local-image-transport.sh"
unset _provenance_dir

simplematch_certification_source_revision() {
  local repo_root="$1"
  local untracked

  git -C "$repo_root" diff --quiet -- . ':(exclude)graphify-out/**' || {
    printf '%s\n' \
      'certification source has tracked working-tree changes; commit or restore them before certification.' >&2
    return 1
  }
  git -C "$repo_root" diff --cached --quiet -- . ':(exclude)graphify-out/**' || {
    printf '%s\n' \
      'certification source has staged changes; commit or restore them before certification.' >&2
    return 1
  }
  untracked="$(
    git -C "$repo_root" ls-files --others --exclude-standard -- . \
      ':(exclude)graphify-out/**'
  )" || return 1
  [[ -z "$untracked" ]] || {
    printf '%s\n' \
      'certification source has untracked repository files; remove or ignore them before certification.' >&2
    printf '%s\n' "$untracked" >&2
    return 1
  }
  git -C "$repo_root" rev-parse HEAD
}

simplematch_record_certification_provenance() {
  local repo_root="$1"
  local evidence_dir="$2"
  local namespace="$3"
  local image_transport="$4"
  local image_tag="$5"
  local image_lock="$6"
  local source_revision verifier_image_reference

  source_revision="$(simplematch_certification_source_revision "$repo_root")" || return 1
  case "$image_transport" in
    registry)
      verifier_image_reference="$(
        simplematch_local_image_lock_digest_reference \
          "$image_lock" risk-matching-e2e-verifier
      )" || return 1
      ;;
    kind-load)
      verifier_image_reference="$(
        simplematch_local_image_inventory_source_image \
          risk-matching-e2e-verifier "$image_tag"
      )" || return 1
      ;;
    *)
      printf 'unsupported certification image transport: %s\n' \
        "$image_transport" >&2
      return 1
      ;;
  esac

  printf '%s\n' "$source_revision" >"$evidence_dir/source-revision" || return 1
  printf '%s\n' "$verifier_image_reference" \
    >"$evidence_dir/verifier-image-reference" || return 1
  printf '%s\n' "$namespace" >"$evidence_dir/retained-namespace" || return 1
}

simplematch_production_like_evidence_dir() {
  local repo_root="$1"
  printf '%s\n' \
    "${SIMPLEMATCH_PRODUCTION_LIKE_EVIDENCE_DIR:-$repo_root/out/certification/local-production-like}"
}

simplematch_certification_verifier_image() {
  local repo_root="$1"
  local expected_namespace="$2"
  local production_like_evidence_dir run_context
  local retained_namespace source_revision current_revision verifier_image_reference

  production_like_evidence_dir="$(simplematch_production_like_evidence_dir "$repo_root")"
  run_context="$production_like_evidence_dir/run-context"

  [[ -f "$run_context" ]] || {
    printf 'production-like run context is missing: %s\n' "$run_context" >&2
    return 1
  }
  [[ -f "$production_like_evidence_dir/source-revision" ]] || {
    printf '%s\n' \
      'production-like source provenance is missing; create a fresh retained run on the current source.' >&2
    return 1
  }
  [[ -f "$production_like_evidence_dir/verifier-image-reference" ]] || {
    printf '%s\n' \
      'production-like verifier image provenance is missing; create a fresh retained run.' >&2
    return 1
  }

  retained_namespace="$(awk -F= '$1 == "namespace" {print substr($0, index($0, "=") + 1)}' "$run_context")"
  [[ -n "$retained_namespace" && "$retained_namespace" == "$expected_namespace" ]] || {
    printf 'production-like evidence belongs to namespace %s, not %s\n' \
      "${retained_namespace:-<unknown>}" "$expected_namespace" >&2
    return 1
  }

  source_revision="$(tr -d '\r\n' <"$production_like_evidence_dir/source-revision")"
  current_revision="$(git -C "$repo_root" rev-parse HEAD)" || return 1
  [[ "$source_revision" == "$current_revision" ]] || {
    printf 'retained production-like source revision %s does not match current revision %s\n' \
      "${source_revision:-<missing>}" "$current_revision" >&2
    printf '%s\n' \
      'Create a fresh production-like certification with --keep-resources before dependent certification.' >&2
    return 1
  }

  verifier_image_reference="$(
    tr -d '\r\n' <"$production_like_evidence_dir/verifier-image-reference"
  )"
  [[ -n "$verifier_image_reference" && "$verifier_image_reference" != *[[:space:]]* ]] || {
    printf '%s\n' 'retained verifier image reference is missing or malformed' >&2
    return 1
  }
  printf '%s\n' "$verifier_image_reference"
}

simplematch_render_verifier_helper_manifest() {
  local source_manifest="$1"
  local verifier_image_reference="$2"
  local destination="$3"

  awk -v image="$verifier_image_reference" '
    BEGIN { replacements = 0 }
    /^[[:space:]]+image: simplematch\/risk-matching-e2e-verifier:/ {
      indentation = $0
      sub(/image:.*/, "", indentation)
      print indentation "image: " image
      replacements += 1
      next
    }
    { print }
    END {
      if (replacements != 1) {
        exit 42
      }
    }
  ' "$source_manifest" >"$destination" || {
    rm -f "$destination"
    printf 'verifier helper manifest must contain exactly one verifier image: %s\n' \
      "$source_manifest" >&2
    return 1
  }
}
