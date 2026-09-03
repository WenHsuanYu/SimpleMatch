#!/usr/bin/env bash

# Incremental certification planning and current-run result materialization.
# Reuse and same-run resume policy belong here; execution mechanics remain in
# the framework and external validity checks are supplied by narrow adapters.

certification_plan_file=""

_certification_planner_now_millis() {
  date +%s%3N
}

certification_execution_timing_json() {
  local started_at_utc="$1"
  local completed_at_utc="$2"
  local duration_millis="$3"

  [[ "$duration_millis" =~ ^[0-9]+$ ]] || return 1
  jq -cn \
    --arg startedAtUtc "$started_at_utc" \
    --arg completedAtUtc "$completed_at_utc" \
    --argjson durationMillis "$duration_millis" '{
      startedAtUtc: $startedAtUtc,
      completedAtUtc: $completedAtUtc,
      durationMillis: $durationMillis
    }'
}

_certification_validate_execution_json() {
  local execution_json="$1"

  jq -e '
    type == "object" and
    (.startedAtUtc | type == "string" and length > 0) and
    (.completedAtUtc | type == "string" and length > 0) and
    (.durationMillis | type == "number" and . >= 0)
  ' <<<"$execution_json" >/dev/null 2>&1
}

certification_phase_result_path() {
  local phase_id="$1"
  certification_phase_policy "$phase_id" >/dev/null || return 1
  printf '%s/phases/%s/result.json\n' "$evidence_dir" "$phase_id"
}

certification_plan_initialize() {
  local run_evidence_dir="$1"

  certification_phase_validate_graph || return 1
  certification_plan_file="$run_evidence_dir/plan.json"
  [[ "$dry_run" == true ]] && return 0
  mkdir -p "$run_evidence_dir/phases" || return 1

  if [[ "${resume:-false}" == true && -f "$certification_plan_file" ]]; then
    jq -e '.schemaVersion == 1 and (.phases | type == "array")' \
      "$certification_plan_file" >/dev/null 2>&1 || {
      printf 'resume certification plan is malformed: %s\n' \
        "$certification_plan_file" >&2
      return 1
    }
  else
    jq -n '{schemaVersion: 1, phases: []}' >"$certification_plan_file" || return 1
  fi

  _certification_plan_record_explicit_skips
}

_certification_plan_put() {
  local phase_id="$1"
  local policy="$2"
  local decision="$3"
  local input_fingerprint="$4"
  local evidence_digest="$5"
  local reason="$6"
  local lookup_duration_millis="${7:-0}"
  local revalidation_duration_millis="${8:-0}"
  local temp_path

  [[ "$lookup_duration_millis" =~ ^[0-9]+$ ]] || return 1
  [[ "$revalidation_duration_millis" =~ ^[0-9]+$ ]] || return 1
  [[ "$dry_run" == true ]] && return 0
  [[ -n "$certification_plan_file" && -f "$certification_plan_file" ]] || return 1
  temp_path="$(mktemp "${certification_plan_file}.tmp.XXXXXX")" || return 1
  jq \
    --arg phase "$phase_id" \
    --arg policy "$policy" \
    --arg decision "$decision" \
    --arg input "$input_fingerprint" \
    --arg evidence "$evidence_digest" \
    --arg reason "$reason" \
    --argjson lookupDurationMillis "$lookup_duration_millis" \
    --argjson revalidationDurationMillis "$revalidation_duration_millis" '
      .phases = (
        [.phases[] | select(.phaseId != $phase)] +
        [{
          phaseId: $phase,
          policy: $policy,
          decision: $decision,
          inputFingerprint: (if $input == "" then null else $input end),
          evidenceDigest: (if $evidence == "" then null else $evidence end),
          reason: $reason,
          lookupDurationMillis: $lookupDurationMillis,
          revalidationDurationMillis: $revalidationDurationMillis
        }]
      )
    ' "$certification_plan_file" >"$temp_path" || {
      rm -f -- "$temp_path"
      return 1
    }
  mv -f -- "$temp_path" "$certification_plan_file" || {
    rm -f -- "$temp_path"
    return 1
  }
}

_certification_plan_record_explicit_skips() {
  local skip_output phase_id reason policy
  local -A recorded=()

  skip_output="$(certification_explicit_skip_entries)" || return 1
  while IFS='|' read -r phase_id reason; do
    [[ -n "$phase_id" ]] || continue
    [[ -z "${recorded[$phase_id]+x}" ]] || continue
    policy="$(certification_phase_policy "$phase_id")" || return 1
    _certification_plan_put \
      "$phase_id" "$policy" SKIP "" "" "$reason" 0 0 || return 1
    recorded["$phase_id"]=true
  done <<<"$skip_output"
}

certification_plan_execute() {
  local dispatcher="$1"
  local phase_id required_output
  local -a required_phases=()

  declare -F "$dispatcher" >/dev/null 2>&1 || {
    printf 'certification phase dispatcher does not exist: %s\n' \
      "$dispatcher" >&2
    return 1
  }
  required_output="$(certification_required_phase_ids)" || return 1
  # Materialize the graph order before dispatching any phase.  A dispatcher may
  # invoke a command that inherits stdin (for example, docker compose exec -T
  # without an explicit input redirect); iterating directly over a here-string
  # would let that command consume the remaining phase IDs and silently truncate
  # the certification run.
  mapfile -t required_phases <<<"$required_output"
  for phase_id in "${required_phases[@]}"; do
    [[ -n "$phase_id" ]] || continue
    "$dispatcher" "$phase_id" || return 1
  done
}

_certification_plan_dependencies_ready() {
  local phase_id="$1"
  local dependency result_path dependency_output

  dependency_output="$(certification_phase_dependencies "$phase_id")" || return 1
  while IFS= read -r dependency; do
    [[ -n "$dependency" ]] || continue
    result_path="$(certification_phase_result_path "$dependency")" || return 1
    [[ -f "$result_path" ]] || {
      printf 'certification phase %s requires unfinished dependency %s\n' \
        "$phase_id" "$dependency" >&2
      return 1
    }
    jq -e '.status == "PASS"' "$result_path" >/dev/null 2>&1 || {
      printf 'certification phase %s requires successful dependency %s\n' \
        "$phase_id" "$dependency" >&2
      return 1
    }
  done <<<"$dependency_output"
}

_certification_cached_outputs_valid() {
  local phase_id="$1"
  local evidence_digest="$2"

  if declare -F certification_phase_cached_outputs_valid >/dev/null 2>&1; then
    certification_phase_cached_outputs_valid "$phase_id" "$evidence_digest"
    return
  fi
  return 0
}

_certification_revalidate_cached_phase() {
  local phase_id="$1"
  local evidence_digest="$2"

  if declare -F certification_phase_revalidate >/dev/null 2>&1; then
    certification_phase_revalidate "$phase_id" "$evidence_digest"
    return
  fi
  return 1
}

certification_plan_phase() {
  local phase_id="$1"
  shift
  local policy input_fingerprint evidence_digest="" decision reason
  local probe_json probe_status probe_reason
  local lookup_started lookup_completed lookup_duration_millis=0
  local revalidation_started revalidation_completed revalidation_duration_millis=0

  policy="$(certification_phase_policy "$phase_id")" || return 1
  if [[ "$dry_run" == true ]]; then
    input_fingerprint="$(
      printf 'dry-run\0%s\0%s\n' "$phase_id" "$*" | certification_sha256_stream
    )" || return 1
    printf 'EXECUTE|%s||dry run does not consume reusable evidence\n' \
      "$input_fingerprint"
    return 0
  fi

  _certification_plan_dependencies_ready "$phase_id" || return 1
  input_fingerprint="$(certification_phase_fingerprint "$phase_id" "$@")" || {
    printf 'unable to establish effective input identity for phase %s\n' \
      "$phase_id" >&2
    return 1
  }

  case "$policy" in
    FRESH)
      decision=EXECUTE
      reason='phase policy requires fresh execution'
      ;;
    CONTENT_ADDRESSED|REVALIDATE)
      lookup_started="$(_certification_planner_now_millis)"
      probe_json="$(certification_evidence_probe \
        "$phase_id" "$input_fingerprint")" || return 1
      lookup_completed="$(_certification_planner_now_millis)"
      lookup_duration_millis=$((lookup_completed - lookup_started))
      probe_status="$(jq -r '.status' <<<"$probe_json")" || return 1
      probe_reason="$(jq -r '.reason' <<<"$probe_json")" || return 1
      evidence_digest="$(jq -r '.evidenceDigest // ""' <<<"$probe_json")" || return 1

      if [[ "$probe_status" != HIT ]]; then
        evidence_digest=""
        decision=EXECUTE
        if [[ "$probe_status" == REJECTED ]]; then
          reason="cache rejected: $probe_reason"
        else
          reason="cache miss: $probe_reason"
        fi
      elif ! _certification_cached_outputs_valid "$phase_id" "$evidence_digest"; then
        evidence_digest=""
        decision=EXECUTE
        reason='cache rejected: reusable outputs no longer validate'
      elif [[ "$policy" == CONTENT_ADDRESSED ]]; then
        decision=REUSE
        reason='exact inputs and reusable outputs validated'
      else
        revalidation_started="$(_certification_planner_now_millis)"
        if _certification_revalidate_cached_phase "$phase_id" "$evidence_digest"; then
          revalidation_completed="$(_certification_planner_now_millis)"
          revalidation_duration_millis=$((
            revalidation_completed - revalidation_started
          ))
          decision=REVALIDATE
          reason='prior evidence passed current external validation'
        else
          revalidation_completed="$(_certification_planner_now_millis)"
          revalidation_duration_millis=$((
            revalidation_completed - revalidation_started
          ))
          evidence_digest=""
          decision=EXECUTE
          reason='cache rejected: current external validation failed'
        fi
      fi
      ;;
    *)
      printf 'unsupported certification phase policy: %s\n' "$policy" >&2
      return 1
      ;;
  esac

  _certification_plan_put \
    "$phase_id" "$policy" "$decision" "$input_fingerprint" \
    "$evidence_digest" "$reason" "$lookup_duration_millis" \
    "$revalidation_duration_millis" || return 1
  printf '%s|%s|%s|%s\n' \
    "$decision" "$input_fingerprint" "$evidence_digest" "$reason"
}

certification_plan_phase_planning_json() {
  local phase_id="$1"

  [[ -f "$certification_plan_file" ]] || return 1
  jq -cer --arg phase "$phase_id" '
    [.phases[] | select(.phaseId == $phase)] as $matches |
    if ($matches | length) != 1 then error("phase plan mismatch") else
      $matches[0] | {
        lookupDurationMillis,
        revalidationDurationMillis
      }
    end
  ' "$certification_plan_file"
}

_certification_phase_outputs_json() {
  local phase_id="$1"
  shift

  if declare -F certification_phase_outputs_json >/dev/null 2>&1; then
    certification_phase_outputs_json "$phase_id" "$@"
    return
  fi
  printf '%s\n' '[]'
}

certification_write_phase_result() {
  local phase_id="$1"
  local result_decision="$2"
  local status="$3"
  local input_fingerprint="$4"
  local evidence_digest="$5"
  local reason="$6"
  local execution_json="$7"
  local outputs_json="$8"
  local result_path temp_path outputs_temp source_revision definition_version planning_json

  _certification_validate_execution_json "$execution_json" || return 1
  jq -e 'type == "array"' <<<"$outputs_json" >/dev/null 2>&1 || return 1
  result_path="$(certification_phase_result_path "$phase_id")" || return 1
  definition_version="$(certification_phase_definition_version "$phase_id")" || return 1
  source_revision="$(git -C "$repo_root" rev-parse HEAD)" || return 1
  planning_json="$(certification_plan_phase_planning_json "$phase_id")" || return 1
  mkdir -p "$(dirname -- "$result_path")" || return 1
  temp_path="$(mktemp "${result_path}.tmp.XXXXXX")" || return 1
  outputs_temp="$(mktemp "${result_path}.outputs.XXXXXX")" || {
    rm -f -- "$temp_path"
    return 1
  }
  printf '%s\n' "$outputs_json" >"$outputs_temp" || {
    rm -f -- "$temp_path" "$outputs_temp"
    return 1
  }

  jq -n \
    --arg phase "$phase_id" \
    --argjson definitionVersion "$definition_version" \
    --arg decision "$result_decision" \
    --arg status "$status" \
    --arg input "$input_fingerprint" \
    --arg evidence "$evidence_digest" \
    --arg reason "$reason" \
    --arg sourceRevision "$source_revision" \
    --argjson execution "$execution_json" \
    --argjson planning "$planning_json" \
    --slurpfile outputs "$outputs_temp" '
      {
        schemaVersion: 1,
        phaseId: $phase,
        definitionVersion: $definitionVersion,
        decision: $decision,
        status: $status,
        inputFingerprint: $input,
        evidenceDigest: (if $evidence == "" then null else $evidence end),
        reason: $reason,
        planning: $planning,
        execution: ($execution + {sourceRevision: $sourceRevision}),
        outputs: $outputs[0]
      }
    ' >"$temp_path" || {
    rm -f -- "$temp_path" "$outputs_temp"
    return 1
  }
  rm -f -- "$outputs_temp" || {
    rm -f -- "$temp_path"
    return 1
  }
  mv -f -- "$temp_path" "$result_path" || {
    rm -f -- "$temp_path"
    return 1
  }
  printf '%s\n' "$result_path"
}

_certification_patch_result_evidence_digest() {
  local result_path="$1"
  local evidence_digest="$2"
  local temp_path

  temp_path="$(mktemp "${result_path}.tmp.XXXXXX")" || return 1
  jq --arg evidence "$evidence_digest" \
    '.evidenceDigest = $evidence' "$result_path" >"$temp_path" || {
    rm -f -- "$temp_path"
    return 1
  }
  mv -f -- "$temp_path" "$result_path" || {
    rm -f -- "$temp_path"
    return 1
  }
}

certification_plan_record_execution() {
  local phase_id="$1"
  local input_fingerprint="$2"
  local reason="$3"
  local execution_json="$4"
  shift 4
  local policy outputs_json result_path evidence_digest=""

  _certification_validate_execution_json "$execution_json" || return 1
  policy="$(certification_phase_policy "$phase_id")" || return 1
  outputs_json="$(_certification_phase_outputs_json "$phase_id" "$@")" || return 1
  jq -e 'type == "array"' <<<"$outputs_json" >/dev/null 2>&1 || return 1
  result_path="$(certification_write_phase_result \
    "$phase_id" EXECUTED PASS "$input_fingerprint" "" "$reason" \
    "$execution_json" "$outputs_json")" || return 1

  if [[ "$policy" != FRESH ]]; then
    evidence_digest="$(
      certification_evidence_publish "$phase_id" "$input_fingerprint" "$result_path"
    )" || {
      printf 'failed to publish reusable evidence for phase %s\n' "$phase_id" >&2
      return 1
    }
    _certification_patch_result_evidence_digest \
      "$result_path" "$evidence_digest" || return 1
  fi
}

certification_plan_record_failure() {
  local phase_id="$1"
  local input_fingerprint="$2"
  local reason="$3"
  local execution_json="$4"

  _certification_validate_execution_json "$execution_json" || return 1
  certification_write_phase_result \
    "$phase_id" EXECUTED FAIL "$input_fingerprint" "" "$reason" \
    "$execution_json" '[]' >/dev/null
}

certification_plan_record_reuse() {
  local phase_id="$1"
  local planner_decision="$2"
  local input_fingerprint="$3"
  local evidence_digest="$4"
  local reason="$5"
  local execution_json="$6"
  local source_evidence outputs_json result_decision

  _certification_validate_execution_json "$execution_json" || return 1
  source_evidence="$evidence_dir/phases/$phase_id/source-evidence.json"
  certification_evidence_materialize "$evidence_digest" "$source_evidence" || return 1
  if declare -F certification_phase_materialize_reused_outputs >/dev/null 2>&1; then
    certification_phase_materialize_reused_outputs \
      "$phase_id" "$evidence_digest" || return 1
  fi
  outputs_json="$(jq -c '.outputs' "$source_evidence")" || return 1
  case "$planner_decision" in
    REUSE) result_decision=REUSED ;;
    REVALIDATE) result_decision=REVALIDATED ;;
    *) return 1 ;;
  esac

  certification_write_phase_result \
    "$phase_id" "$result_decision" PASS "$input_fingerprint" \
    "$evidence_digest" "$reason" "$execution_json" "$outputs_json" >/dev/null
}

certification_phase_resume_result_valid() {
  local phase_id="$1"
  local result_path definition_version

  result_path="$(certification_phase_result_path "$phase_id")" || return 1
  definition_version="$(certification_phase_definition_version "$phase_id")" || return 1
  [[ -f "$result_path" ]] || return 1
  jq -e \
    --arg phase "$phase_id" \
    --argjson definitionVersion "$definition_version" '
      .schemaVersion == 1 and
      .phaseId == $phase and
      .definitionVersion == $definitionVersion and
      .status == "PASS"
    ' "$result_path" >/dev/null 2>&1 || return 1

  if declare -F certification_phase_current_outputs_valid >/dev/null 2>&1; then
    certification_phase_current_outputs_valid "$phase_id" "$result_path" || return 1
  fi
}

certification_phase_resume_decision() {
  local phase_id="$1"
  local required_output="${2:-}"
  local resume_mode

  certification_phase_resume_result_valid "$phase_id" || {
    printf '%s\n' REEXECUTE
    return 0
  }
  [[ -z "$required_output" || -f "$required_output" ]] || {
    printf '%s\n' REEXECUTE
    return 0
  }

  resume_mode="$(certification_phase_resume_mode "$phase_id")" || return 1
  case "$resume_mode" in
    REUSE_RESULT)
      printf '%s\n' RESUME
      ;;
    REEXECUTE)
      printf '%s\n' REEXECUTE
      ;;
    VALIDATE)
      if declare -F certification_phase_resume_validate >/dev/null 2>&1 && \
          certification_phase_resume_validate "$phase_id"; then
        printf '%s\n' RESUME
      else
        printf '%s\n' REJECT
      fi
      ;;
    FORBID)
      printf '%s\n' REJECT
      ;;
    *)
      return 1
      ;;
  esac
}

_certification_plan_validate_structure() {
  jq -e '
    .schemaVersion == 1 and
    (.phases | type == "array") and
    ((.phases | map(.phaseId) | length) ==
      (.phases | map(.phaseId) | unique | length)) and
    ([.phases[] |
      (.lookupDurationMillis | type == "number") and
      (.revalidationDurationMillis | type == "number")
    ] | all)
  ' "$certification_plan_file" >/dev/null 2>&1
}

_certification_write_evidence_manifest() {
  local required_phase_output="$1"
  local phase_id result_path manifest_path temp_path
  local -a result_paths=()

  while IFS= read -r phase_id; do
    [[ -n "$phase_id" ]] || continue
    result_path="$(certification_phase_result_path "$phase_id")" || return 1
    result_paths+=("$result_path")
  done <<<"$required_phase_output"
  ((${#result_paths[@]} > 0)) || return 1

  manifest_path="$evidence_dir/evidence-manifest.json"
  temp_path="$(mktemp "${manifest_path}.tmp.XXXXXX")" || return 1
  jq -s '
    {
      schemaVersion: 1,
      phases: map({
        phaseId,
        definitionVersion,
        decision,
        status,
        inputFingerprint,
        evidenceDigest,
        reason,
        planning,
        execution,
        outputs,
        resultPath: ("phases/" + .phaseId + "/result.json")
      })
    }
  ' "${result_paths[@]}" >"$temp_path" || {
    rm -f -- "$temp_path"
    return 1
  }
  mv -f -- "$temp_path" "$manifest_path" || {
    rm -f -- "$temp_path"
    return 1
  }
}

certification_plan_finalize() {
  local phase_id result_path definition_version plan_count
  local required_phase_output plan_phase_output

  [[ "$dry_run" == true ]] && return 0
  [[ -f "$certification_plan_file" ]] || return 1
  _certification_plan_validate_structure || {
    printf 'certification plan is malformed or contains duplicate phases: %s\n' \
      "$certification_plan_file" >&2
    return 1
  }

  plan_phase_output="$(jq -r '.phases[].phaseId' "$certification_plan_file")" || return 1
  while IFS= read -r phase_id; do
    [[ -n "$phase_id" ]] || continue
    certification_phase_policy "$phase_id" >/dev/null || {
      printf 'certification plan contains unknown phase %s\n' "$phase_id" >&2
      return 1
    }
  done <<<"$plan_phase_output"

  required_phase_output="$(certification_required_phase_ids)" || return 1
  while IFS= read -r phase_id; do
    [[ -n "$phase_id" ]] || continue
    plan_count="$(
      jq -r --arg phase "$phase_id" \
        '[.phases[] | select(.phaseId == $phase)] | length' \
        "$certification_plan_file"
    )" || return 1
    [[ "$plan_count" == 1 ]] || {
      printf 'certification plan is missing required phase %s\n' "$phase_id" >&2
      return 1
    }

    result_path="$(certification_phase_result_path "$phase_id")" || return 1
    [[ -f "$result_path" ]] || {
      printf 'certification plan has no result for required phase %s\n' \
        "$phase_id" >&2
      return 1
    }
    definition_version="$(certification_phase_definition_version "$phase_id")" || return 1
    jq -e \
      --arg phase "$phase_id" \
      --argjson definitionVersion "$definition_version" '
        .schemaVersion == 1 and
        .phaseId == $phase and
        .definitionVersion == $definitionVersion and
        .status == "PASS" and
        (.planning.lookupDurationMillis | type == "number") and
        (.planning.revalidationDurationMillis | type == "number")
      ' "$result_path" >/dev/null 2>&1 || {
      printf 'required certification phase result is invalid: %s\n' \
        "$phase_id" >&2
      return 1
    }
  done <<<"$required_phase_output"

  _certification_write_evidence_manifest "$required_phase_output"
}
