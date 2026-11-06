#!/usr/bin/env bash

# Read-only phase result validation for retained CDC focused diagnostics.
# The retired aggregate no longer creates phase results.

certification_validate_phase_result() {
  local directory="$1" phase="$2"
  local result="$directory/phases/$phase/result.json"

  [[ "$phase" =~ ^[a-z][a-z0-9-]*(/[a-z][a-z0-9-]*)?$ ]] || return 1
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
