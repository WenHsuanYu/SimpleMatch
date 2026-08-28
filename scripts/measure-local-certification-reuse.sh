#!/usr/bin/env bash
set -Eeuo pipefail
IFS=$'\n\t'

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd -- "$script_dir/.." && pwd)"
certification_runner="$script_dir/run-local-production-like-certification.sh"

certification_benchmark_die() {
  printf 'local certification reuse measurement failed: %s\n' "$*" >&2
  return 1
}

certification_benchmark_usage() {
  cat <<'EOF'
Usage:
  scripts/measure-local-certification-reuse.sh [options]

Options:
  --trading-day YYYY-MM-DD  Certification trading day. Defaults to
                            SIMPLEMATCH_CERTIFICATION_TRADING_DAY or today's
                            Asia/Taipei calendar day.
  --tag TAG                 Local image tag. Defaults to
                            SIMPLEMATCH_LOCAL_IMAGE_TAG or local.
  --image-transport MODE    registry (default) or kind-load.
  --output-dir DIR          Measurement output directory. Defaults under
                            out/certification-performance/.
  --help                    Show this help.

This command is a measurement wrapper, not a second certification pipeline.
It runs the normal production-like certification twice against one isolated
reusable evidence cache. The measurement acceptance verdict is based on warm
phase composition, while wall-clock change is recorded as a single-pair
observation rather than a statistical performance claim.
EOF
}

certification_benchmark_now_millis() {
  date +%s%3N
}

certification_benchmark_report_status() {
  local evidence_dir="$1"
  local report="$evidence_dir/report.md"

  [[ -f "$report" ]] || return 1
  awk -F': ' '$1 == "- status" { print $2; exit }' "$report"
}

certification_benchmark_initialize_context() {
  local context_name="$1"
  local -n context="$context_name"

  context=()
  context[tradingDay]="${SIMPLEMATCH_CERTIFICATION_TRADING_DAY:-$(TZ=Asia/Taipei date +%F)}"
  context[imageTag]="${SIMPLEMATCH_LOCAL_IMAGE_TAG:-local}"
  context[imageTransport]="${SIMPLEMATCH_LOCAL_IMAGE_TRANSPORT:-registry}"
  context[outputDir]=""
  context[help]=false
}

certification_benchmark_parse_args() {
  local context_name="$1"
  shift
  local -n context="$context_name"

  while [[ $# -gt 0 ]]; do
    case "$1" in
      --trading-day)
        context[tradingDay]="${2:?--trading-day requires a value}"
        shift 2
        ;;
      --tag)
        context[imageTag]="${2:?--tag requires a value}"
        shift 2
        ;;
      --image-transport)
        context[imageTransport]="${2:?--image-transport requires a value}"
        shift 2
        ;;
      --output-dir)
        context[outputDir]="${2:?--output-dir requires a value}"
        shift 2
        ;;
      --help|-h)
        context[help]=true
        shift
        ;;
      *)
        certification_benchmark_usage >&2
        certification_benchmark_die "unknown option: $1"
        return 1
        ;;
    esac
  done
}

certification_benchmark_validate_environment() {
  local context_name="$1"
  local -n context="$context_name"
  local command_name dirty

  [[ "${context[tradingDay]}" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}$ ]] || {
    certification_benchmark_die \
      "trading day must use YYYY-MM-DD: ${context[tradingDay]}"
    return 1
  }
  case "${context[imageTransport]}" in
    registry|kind-load) ;;
    *)
      certification_benchmark_die \
        "image transport must be registry or kind-load: ${context[imageTransport]}"
      return 1
      ;;
  esac
  for command_name in git jq tee; do
    command -v "$command_name" >/dev/null 2>&1 || {
      certification_benchmark_die "$command_name is required"
      return 1
    }
  done
  [[ -x "$certification_runner" ]] || {
    certification_benchmark_die \
      "certification runner is not executable: $certification_runner"
    return 1
  }

  dirty="$(git -C "$repo_root" status --porcelain --untracked-files=all)"
  [[ -z "$dirty" ]] || {
    certification_benchmark_die \
      'source tree must be clean so cold and warm measurements prove one revision'
    printf '%s\n' "$dirty" >&2
    return 1
  }
  context[sourceRevision]="$(git -C "$repo_root" rev-parse HEAD)" || return 1
  context[deliveryManifest]="$(
    printf '%s' "${SIMPLEMATCH_MARKET_REFERENCE_DELIVERY_MANIFEST:-$repo_root/tools/market-reference-builder/data/${context[tradingDay]}/delivery/manifest.yaml}"
  )"
  [[ -f "${context[deliveryManifest]}" ]] || {
    certification_benchmark_die \
      "approved Market Reference delivery manifest does not exist: ${context[deliveryManifest]}"
    return 1
  }
  context[deliveryManifest]="$(
    cd -- "$(dirname -- "${context[deliveryManifest]}")" &&
      printf '%s/%s' "$PWD" "$(basename -- "${context[deliveryManifest]}")"
  )" || return 1
}

certification_benchmark_prepare_paths() {
  local context_name="$1"
  local -n context="$context_name"

  context[measurementId]="$(date -u +%Y%m%d-%H%M%S)-$$"
  if [[ -z "${context[outputDir]}" ]]; then
    context[outputDir]="$repo_root/out/certification-performance/${context[measurementId]}"
  elif [[ "${context[outputDir]}" != /* ]]; then
    context[outputDir]="$repo_root/${context[outputDir]}"
  fi
  [[ ! -e "${context[outputDir]}" ]] || {
    certification_benchmark_die \
      "output directory already exists: ${context[outputDir]}"
    return 1
  }

  context[cacheDir]="${context[outputDir]}/cache"
  context[coldEvidenceDir]="${context[outputDir]}/cold"
  context[warmEvidenceDir]="${context[outputDir]}/warm"
  context[coldNamespace]="simplematch-local-cert-${context[measurementId]}-cold"
  context[warmNamespace]="simplematch-local-cert-${context[measurementId]}-warm"
  context[coldComposeProject]="simplematch-cert-${context[measurementId]}-cold"
  context[warmComposeProject]="simplematch-cert-${context[measurementId]}-warm"
  context[coldLog]="${context[outputDir]}/cold.log"
  context[warmLog]="${context[outputDir]}/warm.log"
  context[coldPhases]="${context[outputDir]}/cold-phases.json"
  context[warmPhases]="${context[outputDir]}/warm-phases.json"
  context[summaryFile]="${context[outputDir]}/summary.json"
  context[reportFile]="${context[outputDir]}/report.md"

  mkdir -p \
    "${context[cacheDir]}" \
    "${context[coldEvidenceDir]}" \
    "${context[warmEvidenceDir]}" || return 1
}

certification_benchmark_validate_source_state() {
  local context_name="$1"
  local -n context="$context_name"
  local dirty

  [[ "$(git -C "$repo_root" rev-parse HEAD)" == "${context[sourceRevision]}" ]] || {
    certification_benchmark_die 'source revision changed during measurement'
    return 1
  }
  dirty="$(git -C "$repo_root" status --porcelain --untracked-files=all)"
  [[ -z "$dirty" ]] || {
    certification_benchmark_die 'source tree changed during measurement'
    printf '%s\n' "$dirty" >&2
    return 1
  }
}

certification_benchmark_validate_plan() {
  local mode="$1"
  local plan_file="$2"

  [[ "$mode" == cold || "$mode" == warm ]] || return 1
  [[ -f "$plan_file" ]] || return 1

  jq -e --arg mode "$mode" '
    .schemaVersion == 1 and
    (.phases | type == "array" and length > 0) and
    ([.phases[] | select(.decision == "SKIP")] | length == 0) and
    if $mode == "cold" then
      ([.phases[] | .decision == "EXECUTE"] | all)
    else
      ([.phases[] | select(.policy == "FRESH") | .decision == "EXECUTE"] | all) and
      ([.phases[] | select(.policy == "CONTENT_ADDRESSED") | .decision == "REUSE"] | all) and
      ([.phases[] | select(.policy == "REVALIDATE") | .decision == "REVALIDATE"] | all)
    end
  ' "$plan_file" >/dev/null
}

certification_benchmark_run_once() {
  local context_name="$1"
  local label="$2"
  local -n context="$context_name"
  local evidence_key namespace_key compose_key log_key
  local evidence_dir started_millis completed_millis status

  evidence_key="${label}EvidenceDir"
  namespace_key="${label}Namespace"
  compose_key="${label}ComposeProject"
  log_key="${label}Log"
  evidence_dir="${context[$evidence_key]}"

  started_millis="$(certification_benchmark_now_millis)"
  set +e
  env \
    -u SIMPLEMATCH_KAFKA_PRODUCER_CONFIG_FILE \
    -u SIMPLEMATCH_KAFKA_CAPACITY_EVIDENCE_FILE \
    SIMPLEMATCH_CERTIFICATION_CACHE_DIR="${context[cacheDir]}" \
    SIMPLEMATCH_CERTIFICATION_EVIDENCE_DIR="$evidence_dir" \
    SIMPLEMATCH_CERTIFICATION_NAMESPACE="${context[$namespace_key]}" \
    SIMPLEMATCH_CERTIFICATION_COMPOSE_PROJECT="${context[$compose_key]}" \
    SIMPLEMATCH_CERTIFICATION_TRADING_DAY="${context[tradingDay]}" \
    SIMPLEMATCH_MARKET_REFERENCE_DELIVERY_MANIFEST="${context[deliveryManifest]}" \
    SIMPLEMATCH_LOCAL_IMAGE_TAG="${context[imageTag]}" \
    SIMPLEMATCH_LOCAL_IMAGE_TRANSPORT="${context[imageTransport]}" \
    SIMPLEMATCH_LOCAL_IMAGE_LOCK="$evidence_dir/local-images.lock" \
    "$certification_runner" \
      --tag "${context[imageTag]}" \
      --image-transport "${context[imageTransport]}" \
      > >(tee "${context[$log_key]}") 2>&1
  status=$?
  set -e
  completed_millis="$(certification_benchmark_now_millis)"
  printf '%s\n' "$((completed_millis - started_millis))" \
    >"$evidence_dir/wall-clock-millis"

  if [[ "$status" -ne 0 ]]; then
    printf '%s certification failed; evidence: %s\n' "$label" "$evidence_dir" >&2
    return "$status"
  fi
  [[ "$(certification_benchmark_report_status "$evidence_dir")" == PASSED ]] || {
    printf '%s certification did not produce PASSED evidence: %s\n' \
      "$label" "$evidence_dir/report.md" >&2
    return 1
  }
}

certification_benchmark_run_pair() {
  local context_name="$1"
  local -n context="$context_name"

  printf 'Cold certification: %s\n' "${context[coldEvidenceDir]}"
  certification_benchmark_run_once "$context_name" cold || return 1
  certification_benchmark_validate_plan \
    cold "${context[coldEvidenceDir]}/plan.json" || {
    certification_benchmark_die \
      'cold plan reused evidence even though the measurement cache started empty'
    return 1
  }

  certification_benchmark_validate_source_state "$context_name" || return 1

  printf 'Warm certification: %s\n' "${context[warmEvidenceDir]}"
  certification_benchmark_run_once "$context_name" warm || return 1
  certification_benchmark_validate_plan \
    warm "${context[warmEvidenceDir]}/plan.json" || {
    certification_benchmark_die \
      'warm plan did not reuse/revalidate all eligible evidence while executing every FRESH phase'
    return 1
  }

  certification_benchmark_validate_source_state "$context_name"
}

certification_benchmark_build_phase_table() {
  local plan_file="$1"
  local manifest_file="$2"
  local output_file="$3"

  [[ -f "$plan_file" && -f "$manifest_file" ]] || return 1
  jq -n \
    --slurpfile plan "$plan_file" \
    --slurpfile manifest "$manifest_file" '
      [
        $manifest[0].phases[] as $result |
        ($plan[0].phases[] | select(.phaseId == $result.phaseId)) as $planned |
        {
          phaseId: $result.phaseId,
          policy: $planned.policy,
          planDecision: $planned.decision,
          resultDecision: $result.decision,
          durationMillis: $result.execution.durationMillis,
          lookupDurationMillis: $planned.lookupDurationMillis,
          revalidationDurationMillis: $planned.revalidationDurationMillis
        }
      ]
    ' >"$output_file"
}

certification_benchmark_build_summary() {
  local context_name="$1"
  local -n context="$context_name"

  jq -n \
    --arg sourceRevision "${context[sourceRevision]}" \
    --arg tradingDay "${context[tradingDay]}" \
    --arg imageTag "${context[imageTag]}" \
    --arg imageTransport "${context[imageTransport]}" \
    --argjson coldElapsedMillis "${context[coldElapsedMillis]}" \
    --argjson warmElapsedMillis "${context[warmElapsedMillis]}" \
    --slurpfile coldPhases "${context[coldPhases]}" \
    --slurpfile warmPhases "${context[warmPhases]}" '
      def duration_sum($phases):
        ([$phases[].durationMillis] | add) // 0;
      def planning_sum($phases):
        ([
          $phases[] |
          ((.lookupDurationMillis // 0) + (.revalidationDurationMillis // 0))
        ] | add) // 0;
      def decision_counts($phases):
        reduce $phases[] as $phase
          ({}; .[$phase.planDecision] = ((.[$phase.planDecision] // 0) + 1));
      def percent($part; $whole):
        if $whole > 0 then (((($part * 10000) / $whole) | floor) / 100)
        else 0 end;

      ($warmPhases[0] | map(select(.policy == "FRESH"))) as $warmFresh |
      ($warmPhases[0] | map(select(.policy != "FRESH"))) as $warmReusable |
      ($warmFresh | sort_by(.durationMillis) | reverse) as $warmFreshRanked |
      (duration_sum($warmFresh)) as $warmFreshTotal |
      (duration_sum($warmReusable)) as $warmReusableTotal |
      ($warmFreshTotal + $warmReusableTotal) as $warmRecordedTotal |
      (planning_sum($warmReusable)) as $warmReusablePlanning |
      ($warmReusableTotal >= $warmFreshTotal) as $reusableDominates |
      ($warmFreshRanked | map(.phaseId) | index("kafka-broker-failure-live")) as $faultIndex |
      ($warmFreshRanked |
        map(select(.phaseId == "kafka-broker-failure-live")) |
        .[0] // null) as $faultPhase |
      (
        $faultPhase != null and
        $warmFreshTotal > 0 and
        (($faultPhase.durationMillis * 2) >= $warmFreshTotal)
      ) as $faultDominates |
      ($coldElapsedMillis - $warmElapsedMillis) as $savedMillis |
      (
        if $warmElapsedMillis < $coldElapsedMillis then "IMPROVED"
        elif $warmElapsedMillis == $coldElapsedMillis then "UNCHANGED"
        else "REGRESSED"
        end
      ) as $wallClockObservation |
      {
        schemaVersion: 2,
        acceptanceVerdict: (
          if $warmFreshTotal > 0 and ($reusableDominates | not)
          then "PASS" else "FAIL" end
        ),
        acceptanceCriterion: "REUSABLE_WORK_NOT_DOMINANT",
        sourceRevision: $sourceRevision,
        tradingDay: $tradingDay,
        imageTag: $imageTag,
        imageTransport: $imageTransport,
        cold: {
          elapsedMillis: $coldElapsedMillis,
          recordedPhaseMillis: duration_sum($coldPhases[0]),
          decisionCounts: decision_counts($coldPhases[0])
        },
        warm: {
          elapsedMillis: $warmElapsedMillis,
          recordedPhaseMillis: $warmRecordedTotal,
          freshExecutionMillis: $warmFreshTotal,
          reusablePhaseMillis: $warmReusableTotal,
          reusablePlanningMillis: $warmReusablePlanning,
          reusableSharePercent: percent($warmReusableTotal; $warmRecordedTotal),
          reusableWorkDominates: $reusableDominates,
          decisionCounts: decision_counts($warmPhases[0])
        },
        wallClock: {
          observation: $wallClockObservation,
          savedMillis: $savedMillis,
          reductionPercent: (
            if $coldElapsedMillis > 0 then
              ((($savedMillis * 10000) / $coldElapsedMillis | floor) / 100)
            else 0 end
          ),
          samplePairs: 1,
          statisticalClaim: false
        },
        warmFreshPhases: $warmFreshRanked,
        environmentFault: {
          phaseId: "kafka-broker-failure-live",
          durationMillis: ($faultPhase.durationMillis // null),
          rankAmongFreshPhases: (
            if $faultIndex == null then null else ($faultIndex + 1) end
          ),
          shareOfFreshExecutionPercent: (
            if $faultPhase == null then null
            else percent($faultPhase.durationMillis; $warmFreshTotal)
            end
          ),
          dominatesFreshExecution: $faultDominates,
          largestFreshPhase: ($warmFreshRanked[0].phaseId // null),
          followUpIdentitySpecificationRecommended: $faultDominates
        }
      }
    ' >"${context[summaryFile]}"
}

certification_benchmark_write_report() {
  local context_name="$1"
  local -n context="$context_name"

  {
    printf '%s\n\n' '# Local certification reuse measurement'
    jq -r '
      (.environmentFault.rankAmongFreshPhases // "not-recorded") as $faultRank |
      (.environmentFault.shareOfFreshExecutionPercent // "not-recorded") as $faultShare |
      "- acceptance_verdict: \(.acceptanceVerdict)",
      "- acceptance_criterion: \(.acceptanceCriterion)",
      "- source_revision: \(.sourceRevision)",
      "- trading_day: \(.tradingDay)",
      "- image_tag: \(.imageTag)",
      "- image_transport: \(.imageTransport)",
      "- cold_elapsed_ms: \(.cold.elapsedMillis)",
      "- warm_elapsed_ms: \(.warm.elapsedMillis)",
      "- wall_clock_observation: \(.wallClock.observation)",
      "- saved_ms: \(.wallClock.savedMillis)",
      "- reduction_percent: \(.wallClock.reductionPercent)",
      "- statistical_claim: \(.wallClock.statisticalClaim)",
      "- warm_fresh_execution_ms: \(.warm.freshExecutionMillis)",
      "- warm_reusable_phase_ms: \(.warm.reusablePhaseMillis)",
      "- warm_reusable_share_percent: \(.warm.reusableSharePercent)",
      "- warm_reusable_dominates: \(.warm.reusableWorkDominates)",
      "- environment_fault_rank: \($faultRank)",
      "- environment_fault_share_percent: \($faultShare)",
      "- environment_fault_dominates: \(.environmentFault.dominatesFreshExecution)",
      "- environment_fault_follow_up_recommended: \(.environmentFault.followUpIdentitySpecificationRecommended)"
    ' "${context[summaryFile]}" || return 1

    printf '\n%s\n\n' '## Warm fresh phase ranking'
    printf '%s\n' '| Rank | Phase | Duration (ms) |'
    printf '%s\n' '| ---: | --- | ---: |'
    jq -r '
      .warmFreshPhases |
      to_entries[] |
      "| \(.key + 1) | \(.value.phaseId) | \(.value.durationMillis) |"
    ' "${context[summaryFile]}" || return 1

    printf '\n%s\n\n' '## Interpretation'
    jq -r '
      if .acceptanceVerdict == "PASS" then
        "Reusable and revalidated work did not dominate recorded warm phase time."
      else
        "Reusable and revalidated work dominated recorded warm phase time; the incremental certification acceptance criterion is not met."
      end,
      "The wall-clock result is a single-pair observation (\(.wallClock.observation)), not a statistical performance claim.",
      if .environmentFault.followUpIdentitySpecificationRecommended then
        "Kafka broker-failure verification consumed at least half of recorded FRESH execution time. A separate environment-identity specification is justified before considering reuse of that proof."
      else
        "Kafka broker-failure verification did not consume at least half of recorded FRESH execution time. No environment-fault reuse work is justified by this measurement alone."
      end
    ' "${context[summaryFile]}" || return 1
  } >"${context[reportFile]}"
}

certification_benchmark_analyze() {
  local context_name="$1"
  local -n context="$context_name"

  context[coldElapsedMillis]="$(<"${context[coldEvidenceDir]}/wall-clock-millis")"
  context[warmElapsedMillis]="$(<"${context[warmEvidenceDir]}/wall-clock-millis")"

  certification_benchmark_build_phase_table \
    "${context[coldEvidenceDir]}/plan.json" \
    "${context[coldEvidenceDir]}/evidence-manifest.json" \
    "${context[coldPhases]}" || return 1
  certification_benchmark_build_phase_table \
    "${context[warmEvidenceDir]}/plan.json" \
    "${context[warmEvidenceDir]}/evidence-manifest.json" \
    "${context[warmPhases]}" || return 1
  certification_benchmark_build_summary "$context_name" || return 1
  certification_benchmark_write_report "$context_name" || return 1

  cat "${context[reportFile]}"
  printf '\nMeasurement evidence: %s\n' "${context[outputDir]}"
  [[ "$(jq -r '.acceptanceVerdict' "${context[summaryFile]}")" == PASS ]]
}

certification_benchmark_main() {
  local -A benchmark_context=()

  certification_benchmark_initialize_context benchmark_context
  certification_benchmark_parse_args benchmark_context "$@" || return 1
  if [[ "${benchmark_context[help]}" == true ]]; then
    certification_benchmark_usage
    return 0
  fi
  certification_benchmark_validate_environment benchmark_context || return 1
  certification_benchmark_prepare_paths benchmark_context || return 1
  certification_benchmark_run_pair benchmark_context || return 1
  certification_benchmark_analyze benchmark_context
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  certification_benchmark_main "$@"
fi