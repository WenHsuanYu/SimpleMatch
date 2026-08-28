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

This command is a measurement wrapper, not a second certification pipeline. It
runs the normal production-like certification twice. The cold run receives an
empty isolated evidence cache; the warm run receives that same cache. Both runs
still execute every FRESH runtime phase through the normal runner.
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
  local source_revision="$1"
  local trading_day="$2"
  local image_tag="$3"
  local image_transport="$4"
  local cold_elapsed_millis="$5"
  local warm_elapsed_millis="$6"
  local cold_phases="$7"
  local warm_phases="$8"
  local output_file="$9"

  jq -n \
    --arg sourceRevision "$source_revision" \
    --arg tradingDay "$trading_day" \
    --arg imageTag "$image_tag" \
    --arg imageTransport "$image_transport" \
    --argjson coldElapsedMillis "$cold_elapsed_millis" \
    --argjson warmElapsedMillis "$warm_elapsed_millis" \
    --slurpfile coldPhases "$cold_phases" \
    --slurpfile warmPhases "$warm_phases" '
      def decision_counts($phases):
        reduce $phases[] as $phase
          ({}; .[$phase.planDecision] = ((.[$phase.planDecision] // 0) + 1));
      def duration_sum($phases):
        [$phases[].durationMillis] | add // 0;

      ($warmPhases[0] |
        map(select(.policy == "FRESH")) |
        sort_by(.durationMillis) |
        reverse) as $warmFresh |
      (duration_sum($warmFresh)) as $warmFreshTotal |
      ($warmFresh | map(.phaseId) | index("kafka-broker-failure-live")) as $faultIndex |
      ($warmFresh |
        map(select(.phaseId == "kafka-broker-failure-live")) |
        .[0] // null) as $faultPhase |
      ($coldElapsedMillis - $warmElapsedMillis) as $savedMillis |
      {
        schemaVersion: 1,
        sourceRevision: $sourceRevision,
        tradingDay: $tradingDay,
        imageTag: $imageTag,
        imageTransport: $imageTransport,
        verdict: (
          if $warmElapsedMillis < $coldElapsedMillis then "PASS" else "FAIL" end
        ),
        cold: {
          elapsedMillis: $coldElapsedMillis,
          recordedPhaseMillis: duration_sum($coldPhases[0]),
          decisionCounts: decision_counts($coldPhases[0])
        },
        warm: {
          elapsedMillis: $warmElapsedMillis,
          recordedPhaseMillis: duration_sum($warmPhases[0]),
          decisionCounts: decision_counts($warmPhases[0])
        },
        wallClock: {
          savedMillis: $savedMillis,
          reductionPercent: (
            if $coldElapsedMillis > 0 then
              ((($savedMillis * 10000) / $coldElapsedMillis | floor) / 100)
            else 0 end
          )
        },
        warmFreshPhases: $warmFresh,
        environmentFault: {
          phaseId: "kafka-broker-failure-live",
          durationMillis: ($faultPhase.durationMillis // null),
          rankAmongFreshPhases: (
            if $faultIndex == null then null else ($faultIndex + 1) end
          ),
          shareOfFreshExecutionPercent: (
            if $faultPhase == null or $warmFreshTotal == 0 then null
            else (((($faultPhase.durationMillis * 10000) / $warmFreshTotal) | floor) / 100)
            end
          ),
          largestFreshPhase: ($warmFresh[0].phaseId // null),
          followUpIdentitySpecificationRecommended: (
            ($warmFresh[0].phaseId // null) == "kafka-broker-failure-live"
          )
        }
      }
    ' >"$output_file"
}

certification_benchmark_write_report() {
  local summary_file="$1"
  local output_file="$2"

  {
    printf '%s\n\n' '# Local certification reuse measurement'
    jq -r '
      (.environmentFault.rankAmongFreshPhases // "not-recorded") as $faultRank |
      (.environmentFault.shareOfFreshExecutionPercent // "not-recorded") as $faultShare |
      "- verdict: \(.verdict)",
      "- source_revision: \(.sourceRevision)",
      "- trading_day: \(.tradingDay)",
      "- image_tag: \(.imageTag)",
      "- image_transport: \(.imageTransport)",
      "- cold_elapsed_ms: \(.cold.elapsedMillis)",
      "- warm_elapsed_ms: \(.warm.elapsedMillis)",
      "- saved_ms: \(.wallClock.savedMillis)",
      "- reduction_percent: \(.wallClock.reductionPercent)",
      "- environment_fault_rank: \($faultRank)",
      "- environment_fault_share_percent: \($faultShare)",
      "- environment_fault_follow_up_recommended: \(.environmentFault.followUpIdentitySpecificationRecommended)"
    ' "$summary_file" || return 1

    printf '\n%s\n\n' '## Warm fresh phase ranking'
    printf '%s\n' '| Rank | Phase | Duration (ms) |'
    printf '%s\n' '| ---: | --- | ---: |'
    jq -r '
      .warmFreshPhases |
      to_entries[] |
      "| \(.key + 1) | \(.value.phaseId) | \(.value.durationMillis) |"
    ' "$summary_file" || return 1

    printf '\n%s\n\n' '## Interpretation'
    jq -r '
      if .verdict == "PASS" then
        "The unchanged warm run reduced wall-clock time while preserving fresh runtime execution."
      else
        "The warm run preserved policy behavior but did not reduce wall-clock time in this measurement."
      end,
      if .environmentFault.followUpIdentitySpecificationRecommended then
        "Kafka broker-failure verification was the largest recorded fresh phase. A separate environment-identity specification is justified before considering reuse of that proof."
      else
        "Kafka broker-failure verification was not the largest recorded fresh phase. No environment-fault reuse work is justified by this measurement alone."
      end
    ' "$summary_file" || return 1
  } >"$output_file"
}

certification_benchmark_run_once() {
  local label="$1"
  local evidence_dir="$2"
  local namespace="$3"
  local compose_project="$4"
  local cache_dir="$5"
  local trading_day="$6"
  local image_tag="$7"
  local image_transport="$8"
  local log_file="$9"
  local delivery_manifest="${10}"
  local started_millis completed_millis status

  started_millis="$(certification_benchmark_now_millis)"
  set +e
  env \
    -u SIMPLEMATCH_KAFKA_PRODUCER_CONFIG_FILE \
    -u SIMPLEMATCH_KAFKA_CAPACITY_EVIDENCE_FILE \
    SIMPLEMATCH_CERTIFICATION_CACHE_DIR="$cache_dir" \
    SIMPLEMATCH_CERTIFICATION_EVIDENCE_DIR="$evidence_dir" \
    SIMPLEMATCH_CERTIFICATION_NAMESPACE="$namespace" \
    SIMPLEMATCH_CERTIFICATION_COMPOSE_PROJECT="$compose_project" \
    SIMPLEMATCH_CERTIFICATION_TRADING_DAY="$trading_day" \
    SIMPLEMATCH_MARKET_REFERENCE_DELIVERY_MANIFEST="$delivery_manifest" \
    SIMPLEMATCH_LOCAL_IMAGE_TAG="$image_tag" \
    SIMPLEMATCH_LOCAL_IMAGE_TRANSPORT="$image_transport" \
    SIMPLEMATCH_LOCAL_IMAGE_LOCK="$evidence_dir/local-images.lock" \
    "$certification_runner" --tag "$image_tag" --image-transport "$image_transport" \
      > >(tee "$log_file") 2>&1
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

certification_benchmark_main() {
  local trading_day="${SIMPLEMATCH_CERTIFICATION_TRADING_DAY:-$(TZ=Asia/Taipei date +%F)}"
  local image_tag="${SIMPLEMATCH_LOCAL_IMAGE_TAG:-local}"
  local image_transport="${SIMPLEMATCH_LOCAL_IMAGE_TRANSPORT:-registry}"
  local output_dir=""
  local measurement_id source_revision delivery_manifest dirty
  local cache_dir cold_dir warm_dir cold_namespace warm_namespace
  local cold_compose warm_compose cold_elapsed warm_elapsed
  local cold_phases warm_phases summary_file report_file

  while [[ $# -gt 0 ]]; do
    case "$1" in
      --trading-day)
        trading_day="${2:?--trading-day requires a value}"
        shift 2
        ;;
      --tag)
        image_tag="${2:?--tag requires a value}"
        shift 2
        ;;
      --image-transport)
        image_transport="${2:?--image-transport requires a value}"
        shift 2
        ;;
      --output-dir)
        output_dir="${2:?--output-dir requires a value}"
        shift 2
        ;;
      --help|-h)
        certification_benchmark_usage
        return 0
        ;;
      *)
        certification_benchmark_usage >&2
        certification_benchmark_die "unknown option: $1"
        return 1
        ;;
    esac
  done

  [[ "$trading_day" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}$ ]] || {
    certification_benchmark_die "trading day must use YYYY-MM-DD: $trading_day"
    return 1
  }
  case "$image_transport" in
    registry|kind-load) ;;
    *)
      certification_benchmark_die \
        "image transport must be registry or kind-load: $image_transport"
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
  source_revision="$(git -C "$repo_root" rev-parse HEAD)"

  delivery_manifest="${SIMPLEMATCH_MARKET_REFERENCE_DELIVERY_MANIFEST:-$repo_root/tools/market-reference-builder/data/${trading_day}/delivery/manifest.yaml}"
  [[ -f "$delivery_manifest" ]] || {
    certification_benchmark_die \
      "approved Market Reference delivery manifest does not exist: $delivery_manifest"
    return 1
  }
  delivery_manifest="$(cd -- "$(dirname -- "$delivery_manifest")" && pwd)/$(basename -- "$delivery_manifest")"

  measurement_id="$(date -u +%Y%m%d-%H%M%S)-$$"
  if [[ -z "$output_dir" ]]; then
    output_dir="$repo_root/out/certification-performance/$measurement_id"
  elif [[ "$output_dir" != /* ]]; then
    output_dir="$repo_root/$output_dir"
  fi
  [[ ! -e "$output_dir" ]] || {
    certification_benchmark_die "output directory already exists: $output_dir"
    return 1
  }

  cache_dir="$output_dir/cache"
  cold_dir="$output_dir/cold"
  warm_dir="$output_dir/warm"
  cold_namespace="simplematch-local-cert-${measurement_id}-cold"
  warm_namespace="simplematch-local-cert-${measurement_id}-warm"
  cold_compose="simplematch-cert-${measurement_id}-cold"
  warm_compose="simplematch-cert-${measurement_id}-warm"
  mkdir -p "$cache_dir" "$cold_dir" "$warm_dir"

  printf 'Cold certification: %s\n' "$cold_dir"
  certification_benchmark_run_once \
    cold "$cold_dir" "$cold_namespace" "$cold_compose" "$cache_dir" \
    "$trading_day" "$image_tag" "$image_transport" "$output_dir/cold.log" \
    "$delivery_manifest" || return 1
  certification_benchmark_validate_plan cold "$cold_dir/plan.json" || {
    certification_benchmark_die \
      'cold plan reused evidence even though the measurement cache started empty'
    return 1
  }

  [[ "$(git -C "$repo_root" rev-parse HEAD)" == "$source_revision" ]] || {
    certification_benchmark_die 'source revision changed between cold and warm runs'
    return 1
  }

  printf 'Warm certification: %s\n' "$warm_dir"
  certification_benchmark_run_once \
    warm "$warm_dir" "$warm_namespace" "$warm_compose" "$cache_dir" \
    "$trading_day" "$image_tag" "$image_transport" "$output_dir/warm.log" \
    "$delivery_manifest" || return 1
  certification_benchmark_validate_plan warm "$warm_dir/plan.json" || {
    certification_benchmark_die \
      'warm plan did not reuse/revalidate all eligible evidence while executing every FRESH phase'
    return 1
  }

  [[ "$(git -C "$repo_root" rev-parse HEAD)" == "$source_revision" ]] || {
    certification_benchmark_die 'source revision changed during measurement'
    return 1
  }

  cold_elapsed="$(<"$cold_dir/wall-clock-millis")"
  warm_elapsed="$(<"$warm_dir/wall-clock-millis")"
  cold_phases="$output_dir/cold-phases.json"
  warm_phases="$output_dir/warm-phases.json"
  summary_file="$output_dir/summary.json"
  report_file="$output_dir/report.md"

  certification_benchmark_build_phase_table \
    "$cold_dir/plan.json" "$cold_dir/evidence-manifest.json" "$cold_phases" || return 1
  certification_benchmark_build_phase_table \
    "$warm_dir/plan.json" "$warm_dir/evidence-manifest.json" "$warm_phases" || return 1
  certification_benchmark_build_summary \
    "$source_revision" "$trading_day" "$image_tag" "$image_transport" \
    "$cold_elapsed" "$warm_elapsed" "$cold_phases" "$warm_phases" \
    "$summary_file" || return 1
  certification_benchmark_write_report "$summary_file" "$report_file" || return 1

  cat "$report_file"
  printf '\nMeasurement evidence: %s\n' "$output_dir"
  [[ "$(jq -r '.verdict' "$summary_file")" == PASS ]]
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  certification_benchmark_main "$@"
fi
