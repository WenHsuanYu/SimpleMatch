#!/usr/bin/env bash

# Provenance helpers shared by the production-like runner and dependent
# certification workflows. A retained namespace is valid evidence only when the
# verifier process and repository revision come from the same clean source tree.

_provenance_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/lib/local-image-transport.sh
source "$_provenance_dir/local-image-transport.sh"
unset _provenance_dir

simplematch_certification_source_revision() {
  local repo_root="$1"
  local untracked_source

  if ! git -C "$repo_root" diff --quiet --ignore-submodules -- \
        . ':(exclude)graphify-out/**' ||
     ! git -C "$repo_root" diff --cached --quiet --ignore-submodules -- \
        . ':(exclude)graphify-out/**'; then
    printf '%s\n' \
      'certification source has tracked working-tree changes; commit or restore them before certification.' \
      >&2
    return 1
  fi

  untracked_source="$(
    git -C "$repo_root" ls-files --others --exclude-standard -- \
      . ':(exclude)graphify-out/**'
  )" || return 1
  if [[ -n "$untracked_source" ]]; then
    printf '%s\n' \
      'certification source has untracked repository files; commit, ignore, or remove them before certification.' \
      >&2
    printf '%s\n' "$untracked_source" >&2
    return 1
  fi

  git -C "$repo_root" rev-parse HEAD
}

simplematch_certification_verifier_image_identity() {
  local production_like_evidence_dir="$1"
  local identity_file="$production_like_evidence_dir/verifier-image-identity"
  local identity

  [[ -f "$identity_file" ]] || {
    printf '%s\n' 'production-like verifier image identity is missing; create a fresh retained run.' >&2
    return 1
  }
  identity="$(tr -d '\r\n' <"$identity_file")" || return 1
  [[ "$identity" =~ ^sha256:[0-9a-f]{64}$ ]] || {
    printf '%s\n' 'retained verifier image identity is malformed' >&2
    return 1
  }
  printf '%s\n' "$identity"
}

simplematch_certification_image_transport() {
  local production_like_evidence_dir="$1"
  local run_context="$production_like_evidence_dir/run-context"
  local image_transport

  image_transport="$(
    awk -F= '$1 == "image_transport" {print substr($0, index($0, "=") + 1)}' \
      "$run_context"
  )" || return 1
  simplematch_local_image_transport_validate "$image_transport" || return 1
  printf '%s\n' "$image_transport"
}

simplematch_record_certification_provenance() {
  local repo_root="$1"
  local evidence_dir="$2"
  local namespace="$3"
  local image_transport="$4"
  local image_tag="$5"
  local image_lock="$6"
  local source_revision verifier_image_reference verifier_image_identity

  source_revision="$(simplematch_certification_source_revision "$repo_root")" || return 1
  case "$image_transport" in
    registry)
      verifier_image_reference="$(
        simplematch_local_image_lock_digest_reference \
          "$image_lock" risk-matching-e2e-verifier
      )" || return 1
      verifier_image_identity="${verifier_image_reference##*@}"
      ;;
    kind-load)
      verifier_image_reference="$(
        simplematch_local_image_inventory_source_image \
          risk-matching-e2e-verifier "$image_tag"
      )" || return 1
      verifier_image_identity="$(
        docker image inspect --format '{{.Id}}' "$verifier_image_reference"
      )" || return 1
      ;;
    *)
      printf 'unsupported certification image transport: %s\n' \
        "$image_transport" >&2
      return 1
      ;;
  esac
  [[ "$verifier_image_identity" =~ ^sha256:[0-9a-f]{64}$ ]] || {
    printf 'verifier image does not expose an immutable OCI identity: %s\n' \
      "$verifier_image_reference" >&2
    return 1
  }

  printf '%s\n' "$source_revision" >"$evidence_dir/source-revision" || return 1
  printf '%s\n' "$verifier_image_reference" \
    >"$evidence_dir/verifier-image-reference" || return 1
  printf '%s\n' "$verifier_image_identity" \
    >"$evidence_dir/verifier-image-identity" || return 1
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
  local production_like_evidence_dir="${3:-}"
  local run_context retained_namespace source_revision current_revision
  local verifier_image_reference verifier_image_identity image_transport

  if [[ -z "$production_like_evidence_dir" ]]; then
    production_like_evidence_dir="$(simplematch_production_like_evidence_dir "$repo_root")" || return 1
  fi
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

  retained_namespace="$(
    awk -F= '$1 == "namespace" {print substr($0, index($0, "=") + 1)}' \
      "$run_context"
  )" || return 1
  [[ -n "$retained_namespace" && "$retained_namespace" == "$expected_namespace" ]] || {
    printf 'production-like evidence belongs to namespace %s, not %s\n' \
      "${retained_namespace:-<unknown>}" "$expected_namespace" >&2
    return 1
  }

  source_revision="$(tr -d '\r\n' <"$production_like_evidence_dir/source-revision")" || return 1
  current_revision="$(simplematch_certification_source_revision "$repo_root")" || return 1
  [[ "$source_revision" == "$current_revision" ]] || {
    printf 'retained production-like source revision %s does not match current revision %s\n' \
      "${source_revision:-<missing>}" "$current_revision" >&2
    printf '%s\n' \
      'Create a fresh production-like certification with --keep-resources before dependent certification.' >&2
    return 1
  }

  verifier_image_reference="$(
    tr -d '\r\n' <"$production_like_evidence_dir/verifier-image-reference"
  )" || return 1
  [[ -n "$verifier_image_reference" && "$verifier_image_reference" != *[[:space:]]* ]] || {
    printf '%s\n' 'retained verifier image reference is missing or malformed' >&2
    return 1
  }
  verifier_image_identity="$(
    simplematch_certification_verifier_image_identity "$production_like_evidence_dir"
  )" || return 1
  image_transport="$(
    simplematch_certification_image_transport "$production_like_evidence_dir"
  )" || return 1
  if [[ "$image_transport" == registry \
        && "$verifier_image_reference" != *@"$verifier_image_identity" ]]; then
    printf '%s\n' 'registry verifier reference does not match its retained digest identity' >&2
    return 1
  fi

  printf '%s\n' "$verifier_image_reference"
}

simplematch_verify_kind_loaded_verifier_image_execution() {
  local repo_root="$1"
  local expected_namespace="$2"
  local retained_evidence_dir="$3"
  local image_transport expected_identity verifier_image_reference nodes node actual_identity probe_name

  image_transport="$(simplematch_certification_image_transport "$retained_evidence_dir")" || return 1
  [[ "$image_transport" == kind-load ]] || return 0
  command -v docker >/dev/null 2>&1 || return 1
  command -v kubectl >/dev/null 2>&1 || return 1

  expected_identity="$(
    simplematch_certification_verifier_image_identity "$retained_evidence_dir"
  )" || return 1
  verifier_image_reference="$(
    simplematch_certification_verifier_image \
      "$repo_root" "$expected_namespace" "$retained_evidence_dir"
  )" || return 1
  nodes="$(
    kubectl get nodes -o json | jq -r '
      .items[]
      | select(.spec.unschedulable != true)
      | select(([.spec.taints[]?.effect] | index("NoSchedule")) == null)
      | .metadata.name
    '
  )" || return 1
  [[ -n "$nodes" ]] || return 1

  while IFS= read -r node; do
    [[ -n "$node" ]] || continue
    actual_identity="$(
      docker exec "$node" crictl inspecti "$verifier_image_reference" |
        jq -er '.status.id | select(type == "string" and test("^sha256:[0-9a-f]{64}$"))'
    )" || return 1
    [[ "$actual_identity" == "$expected_identity" ]] || return 1
    probe_name="simplematch-verifier-image-probe-$RANDOM-$$"
    docker exec "$node" ctr -n k8s.io run --rm --net-host \
      "$verifier_image_reference" "$probe_name" /bin/sh -c true || return 1
  done <<<"$nodes"
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
