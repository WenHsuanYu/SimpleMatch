#!/usr/bin/env bash

# Current-run results only. Historical PASS results never select or skip work.

declare -g evidence_dir run_id source_signature

certification_write_phase_result() {
  local phase="$1" status="$2" exit_code="${3:-null}"
  local result="$evidence_dir/phases/$phase/result.json" temporary revision

  _certification_require_known_phase "$phase" || return 1
  revision="$(cat "$evidence_dir/source-revision")" || return 1
  mkdir -p "$(dirname -- "$result")" || return 1
  temporary="$(mktemp "${result}.tmp.XXXXXX")" || return 1
  if ! jq -n --arg phase "$phase" --arg status "$status" \
      --arg run "$run_id" --arg signature "$source_signature" \
      --arg revision "$revision" --arg recorded "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
      --argjson exitCode "$exit_code" '{schemaVersion:2,runId:$run,phaseId:$phase,
        status:$status,exitCode:$exitCode,sourceSignature:$signature,
        execution:{sourceRevision:$revision,recordedAtUtc:$recorded}}' >"$temporary"; then
    rm -f -- "$temporary"
    return 1
  fi
  mv -f -- "$temporary" "$result"
}

certification_validate_phase_result() {
  local directory="$1" phase="$2"
  local result="$directory/phases/$phase/result.json"

  _certification_require_known_phase "$phase" || return 1
  [[ -f "$result" && ! -L "$result" && -f "$directory/run-context" && \
    ! -L "$directory/run-context" && -f "$directory/source-revision" && \
    ! -L "$directory/source-revision" ]] || return 1
  [[ "$(realpath -ms -- "$result")" == "$(realpath -m -- "$result")" ]] || return 1
  jq -e -s --arg phase "$phase" --rawfile context "$directory/run-context" \
      --rawfile revision "$directory/source-revision" '
    def context_value($key):
      [$context | split("\n")[] | select(startswith($key + "=")) |
        ltrimstr($key + "=")] |
      if length == 1 then .[0] else error("invalid run context") end;
    context_value("run_id") as $run |
    context_value("source_signature") as $signature |
    ($revision | sub("[\r\n]+$"; "")) as $source |
    length == 1 and (.[0] as $result |
      ($run | test("^[A-Za-z0-9_.-]+$")) and
      ($signature | test("^[0-9a-f]{64}$")) and
      ($source | test("^[0-9a-f]{40}$")) and
      $result.schemaVersion == 2 and $result.phaseId == $phase and
      $result.runId == $run and $result.sourceSignature == $signature and
      $result.status == "PASS" and $result.exitCode == 0 and
      $result.execution.sourceRevision == $source)
  ' "$result" >/dev/null
}

certification_write_completion_manifest() {
  local directory="$1" required="$2" phase temporary
  local -a results=()

  while IFS= read -r phase; do
    [[ -n "$phase" ]] || continue
    certification_validate_phase_result "$directory" "$phase" || {
      printf 'Certification phase has no current-run PASS: %s\n' "$phase" >&2
      return 1
    }
    results+=("$directory/phases/$phase/result.json")
  done <<<"$required"
  ((${#results[@]} > 0)) || return 1
  temporary="$(mktemp "$directory/evidence-manifest.json.tmp.XXXXXX")" || return 1
  if ! jq -s '{schemaVersion:2,runId:.[0].runId,sourceSignature:.[0].sourceSignature,
      sourceRevision:.[0].execution.sourceRevision,phases:map(.phaseId)}' \
      "${results[@]}" >"$temporary"; then
    rm -f -- "$temporary"
    return 1
  fi
  mv -f -- "$temporary" "$directory/evidence-manifest.json"
}

certification_validate_completion() {
  local directory="$1" required="$2" phase first="" phase_json
  local manifest="$directory/evidence-manifest.json"

  [[ -f "$manifest" && ! -L "$manifest" ]] || return 1
  while IFS= read -r phase; do
    [[ -n "$phase" ]] || continue
    certification_validate_phase_result "$directory" "$phase" || return 1
    [[ -n "$first" ]] || first="$directory/phases/$phase/result.json"
  done <<<"$required"
  [[ -n "$first" ]] || return 1
  phase_json="$(printf '%s\n' "$required" | jq -Rsc 'split("\n") | map(select(length > 0))')" || return 1
  jq -e -s --argjson required "$phase_json" --slurpfile result "$first" '
    length == 1 and (.[0] as $manifest |
      $manifest.schemaVersion == 2 and $manifest.phases == $required and
      $manifest.runId == $result[0].runId and
      $manifest.sourceSignature == $result[0].sourceSignature and
      $manifest.sourceRevision == $result[0].execution.sourceRevision)
  ' "$manifest" >/dev/null
}
