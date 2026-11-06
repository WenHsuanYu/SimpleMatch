#!/usr/bin/env bash

# Source and verifier identities for retained CDC focused diagnostics.
# No aggregate provenance writer or retired verifier image adapter remains.

_provenance_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/lib/local-image-transport.sh
source "$_provenance_dir/local-image-transport.sh"
# shellcheck source=scripts/lib/local-certification-runtime-provenance.sh
source "$_provenance_dir/local-certification-runtime-provenance.sh"
unset _provenance_dir

simplematch_certification_source_signature() {
  local repo_root="$1"
  local path
  local -a runtime_source_paths=()

  mapfile -t runtime_source_paths < <(
    simplematch_certification_runtime_source_paths
  )
  {
    git -C "$repo_root" rev-parse HEAD
    git -C "$repo_root" ls-files -co --exclude-standard -- \
      "${runtime_source_paths[@]}" | LC_ALL=C sort -u |
      while IFS= read -r path; do
        [[ -n "$path" ]] || continue
        if [[ -e "$repo_root/$path" || -L "$repo_root/$path" ]]; then
          sha256sum "$repo_root/$path" || return 1
        else
          printf 'deleted\t%s\n' "$path"
        fi
      done
  } | sha256sum | awk '{print $1}'
}

# Focused CDC diagnostics reuse a retained Kubernetes runtime only when the
# inputs that created that runtime are unchanged. The full certification source
# signature intentionally covers every non-document source file; using it here
# would make an unrelated commit invalidate a narrowly scoped observer. Keep
# these path sets explicit so a runtime or verifier change is reviewed as a
# deliberate provenance boundary instead of being hidden behind a broad
# "skip" switch.
#
# Verifier scope covers the observer, evidence interpretation, and focused
# diagnostic adapters. It may change while a retained runtime remains valid;
# every focused invocation still runs the current verifier and writes a new
# diagnostic report.
simplematch_certification_cdc_verifier_source_paths() {
  printf '%s\n' \
    scripts/lib/local-certification-runtime-provenance.sh \
    scripts/lib/local-certification-provenance.sh \
    scripts/lib/local-certification-focused-diagnostic.sh \
    scripts/lib/cdc-observer-fixture.sh \
    scripts/lib/cdc-verifier.sh \
    tools/recovery-evidence-verifier/src/main \
    tools/recovery-evidence-verifier/build.gradle.kts \
    tools/recovery-evidence-verifier/gradle.lockfile \
    scripts/lib/local-resilience.sh \
    scripts/run-local-cdc-delivery-focused-diagnostic.sh \
    scripts/run-risk-cdc-delivery-observer-check.sh \
    scripts/test-cdc-observer-fixture-contract.sh \
    scripts/test-local-certification-focused-diagnostic.sh
}

simplematch_certification_cdc_verifier_signature() {
  local repo_root="$1"
  local contract_script="${2:-}"
  local observer_script="${3:-}"
  local path_output
  local base_signature contract_path contract_reference contract_digest
  local observer_path observer_reference observer_digest
  local -a paths=()
  path_output="$(simplematch_certification_cdc_verifier_source_paths)" || return 1
  mapfile -t paths <<<"$path_output"
  base_signature="$(simplematch_certification_scoped_source_signature \
    "$repo_root" cdc-verifier "${paths[@]}")" || return 1
  contract_path="$(simplematch_certification_cdc_verifier_contract_path \
    "$repo_root" "$contract_script")" || return 1
  contract_reference="$(simplematch_certification_cdc_verifier_contract_reference \
    "$repo_root" "$contract_path")" || return 1
  contract_digest="$(simplematch_certification_cdc_verifier_contract_sha256 \
    "$contract_path")" || return 1
  observer_path="$(simplematch_certification_cdc_verifier_observer_path \
    "$repo_root" "$observer_script")" || return 1
  observer_reference="$(simplematch_certification_cdc_verifier_observer_reference \
    "$repo_root" "$observer_path")" || return 1
  observer_digest="$(simplematch_certification_cdc_verifier_observer_sha256 \
    "$observer_path")" || return 1
  printf 'base\t%s\ncontract\t%s\ncontract-sha256\t%s\nobserver\t%s\nobserver-sha256\t%s\n' \
    "$base_signature" "$contract_reference" "$contract_digest" \
    "$observer_reference" "$observer_digest" |
    sha256sum | awk '{print $1}'
}

simplematch_certification_cdc_verifier_contract_path() {
  local repo_root="$1"
  local requested="${2:-${SIMPLEMATCH_CDC_OBSERVER_CONTRACT_SCRIPT:-}}"
  local directory filename

  requested="${requested:-$repo_root/scripts/test-cdc-observer-fixture-contract.sh}"
  [[ "$requested" == /* ]] || requested="$repo_root/$requested"
  [[ -f "$requested" && ! -L "$requested" && -r "$requested" ]] || {
    printf 'CDC verifier contract is missing, symlinked, or not readable: %s\n' \
      "$requested" >&2
    return 1
  }
  directory="${requested%/*}"
  filename="${requested##*/}"
  [[ "$directory" != "$requested" ]] || directory=.
  directory="$(cd -- "$directory" && pwd)" || return 1
  printf '%s/%s\n' "$directory" "$filename"
}

simplematch_certification_cdc_verifier_contract_reference() {
  local repo_root="$1"
  local contract_path="$2"
  case "$contract_path" in
    "$repo_root"/*) printf '%s\n' "${contract_path#"$repo_root"/}" ;;
    *) printf '%s\n' "$contract_path" ;;
  esac
}

simplematch_certification_cdc_verifier_contract_sha256() {
  local contract_path="$1"
  sha256sum "$contract_path" | awk '{print $1}'
}

simplematch_certification_cdc_verifier_observer_path() {
  local repo_root="$1"
  local requested="${2:-${SIMPLEMATCH_CDC_OBSERVER_SCRIPT:-}}"
  local directory filename

  requested="${requested:-$repo_root/scripts/run-risk-cdc-delivery-observer-check.sh}"
  [[ "$requested" == /* ]] || requested="$repo_root/$requested"
  [[ -f "$requested" && ! -L "$requested" && -r "$requested" && -x "$requested" ]] || {
    printf 'CDC observer is missing, symlinked, unreadable, or not executable: %s\n' \
      "$requested" >&2
    return 1
  }
  directory="${requested%/*}"
  filename="${requested##*/}"
  [[ "$directory" != "$requested" ]] || directory=.
  directory="$(cd -- "$directory" && pwd)" || return 1
  printf '%s/%s\n' "$directory" "$filename"
}

simplematch_certification_cdc_verifier_observer_reference() {
  local repo_root="$1"
  local observer_path="$2"
  case "$observer_path" in
    "$repo_root"/*) printf '%s\n' "${observer_path#"$repo_root"/}" ;;
    *) printf '%s\n' "$observer_path" ;;
  esac
}

simplematch_certification_cdc_verifier_observer_sha256() {
  local observer_path="$1"
  sha256sum "$observer_path" | awk '{print $1}'
}
