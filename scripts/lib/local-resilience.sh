#!/usr/bin/env bash

set -euo pipefail

resilience_log_is_safe() {
  local path="$1" grep_status
  [[ -f "$path" ]] || return 1
  if grep -Eiq '8=FIX|(^|[^[:alnum:]_])(35|49|56|55|54|38|44)=[^[:space:]]+|password[=:]|secret[=:]|credentials?[=:]|raw[_ -]?fix|complete[_ -]?account[_ -]?payload|"?(password|secret|credentials?|token|access[_ -]?token|refresh[_ -]?token|private[_ -]?key|client[_ -]?secret)"?[[:space:]]*:[[:space:]]*"?[^"[:space:],}]+|Bearer[[:space:]]+[A-Za-z0-9._~+/-]+=*' "$path"; then
    return 1
  else
    grep_status=$?
  fi
  [[ "$grep_status" -eq 1 ]]
}

# Prepare deterministic Java work before a diagnostic can inject a fault.
resilience_prepare_evidence_verifier() {
  local repo_root="$1"
  timeout --foreground 120 "$repo_root/gradlew" -q -p "$repo_root" \
    :tools:recovery-evidence-verifier:installDist || return 1
  RESILIENCE_EVIDENCE_VERIFIER_EXECUTABLE="$repo_root/tools/recovery-evidence-verifier/build/install/recovery-evidence-verifier/bin/recovery-evidence-verifier"
  [[ -x "$RESILIENCE_EVIDENCE_VERIFIER_EXECUTABLE" ]]
}

resilience_verify_evidence() {
  [[ -x "${RESILIENCE_EVIDENCE_VERIFIER_EXECUTABLE:-}" ]] || return 1
  "$RESILIENCE_EVIDENCE_VERIFIER_EXECUTABLE" "$@"
}
