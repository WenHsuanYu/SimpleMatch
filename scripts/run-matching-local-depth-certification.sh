#!/usr/bin/env bash

set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd -- "$script_dir/.." && pwd)"
timestamp="$(date -u +%Y%m%dT%H%M%SZ)"
report_dir="${SIMPLEMATCH_DEPTH_EVIDENCE_DIR:-$repo_root/out/certification/matching-local-depth/$timestamp}"
cycles="${SIMPLEMATCH_DEPTH_SOAK_CYCLES:-10}"
warmup="${SIMPLEMATCH_DEPTH_WARMUP_ITERATIONS:-10}"
iterations="${SIMPLEMATCH_DEPTH_ITERATIONS:-34}"
maximum_resting_orders="${SIMPLEMATCH_DEPTH_MAXIMUM_RESTING_ORDERS:-256}"
cpuset="${SIMPLEMATCH_BENCHMARK_CPUSET:-}"
require_pinned="${SIMPLEMATCH_REQUIRE_PINNED:-false}"
benchmark_binary="${SIMPLEMATCH_MATCHING_BENCHMARK_BIN:-$repo_root/out/build/full-native-dev/simplematch-matching-capacity-benchmark}"

usage() {
  cat <<'EOF'
Usage: scripts/run-matching-local-depth-certification.sh [options]

Options:
  --report-dir DIR              Evidence directory.
  --cycles N                    Bounded local soak cycles (default: 10).
  --warmup N                    Warmup iterations per cycle (default: 10).
  --iterations N                Measured iterations per cycle (default: 34).
  --maximum-resting-orders N    Core resting-order bound (default: 256).
  --cpuset CPUSET               Optional taskset CPU set.
  --require-pinned              Require one effective CPU when --cpuset is set.
  --help                        Show this help.

The default profile is the bounded side-project local-day envelope: 150 books,
34 iterations, 10,200 commands and 10,200 events per cycle. Ten cycles are a
bounded soak, not a production endurance or latency certification.
EOF
}

die() {
  printf 'matching local depth certification: %s\n' "$*" >&2
  exit 2
}

positive_integer() {
  [[ "$1" =~ ^[1-9][0-9]*$ ]]
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --report-dir) report_dir="${2:?--report-dir requires a value}"; shift 2 ;;
    --cycles) cycles="${2:?--cycles requires a value}"; shift 2 ;;
    --warmup) warmup="${2:?--warmup requires a value}"; shift 2 ;;
    --iterations) iterations="${2:?--iterations requires a value}"; shift 2 ;;
    --maximum-resting-orders)
      maximum_resting_orders="${2:?--maximum-resting-orders requires a value}"
      shift 2
      ;;
    --cpuset) cpuset="${2:?--cpuset requires a value}"; shift 2 ;;
    --require-pinned) require_pinned=true; shift ;;
    --help|-h) usage; exit 0 ;;
    *) usage >&2; die "unknown option: $1" ;;
  esac
done

for value in "$cycles" "$warmup" "$iterations" "$maximum_resting_orders"; do
  positive_integer "$value" || die "numeric options must be positive integers"
done
[[ -x "$benchmark_binary" ]] || die "benchmark is missing or not executable: $benchmark_binary"
if [[ "$require_pinned" == true && -z "$cpuset" ]]; then
  die "--require-pinned requires --cpuset"
fi

mkdir -p "$report_dir/cycles"
reports=()
for cycle in $(seq 1 "$cycles"); do
  cycle_report="$report_dir/cycles/cycle-$(printf '%02d' "$cycle").json"
  command=(
    env
    SIMPLEMATCH_BENCHMARK_REPORT="$cycle_report"
    SIMPLEMATCH_MATCHING_BENCHMARK_BIN="$benchmark_binary"
    SIMPLEMATCH_REQUIRE_PINNED="$require_pinned"
    bash "$repo_root/scripts/run-matching-capacity-certification.sh"
    --warmup "$warmup"
    --iterations "$iterations"
    --maximum-resting-orders "$maximum_resting_orders"
  )
  if [[ -n "$cpuset" ]]; then
    command=(env SIMPLEMATCH_BENCHMARK_CPUSET="$cpuset" "${command[@]}")
  fi
  "${command[@]}" >"$report_dir/cycles/cycle-$(printf '%02d' "$cycle").stdout" \
    2>"$report_dir/cycles/cycle-$(printf '%02d' "$cycle").stderr"
  reports+=("$cycle_report")
done

cycle_results="$(jq -s '.' "${reports[@]}")"
jq -n \
  --arg generated_at_utc "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
  --arg binary "$benchmark_binary" --arg report_dir "$report_dir" \
  --argjson cycles "$cycles" --argjson warmup "$warmup" \
  --argjson iterations "$iterations" --argjson maximum_resting_orders "$maximum_resting_orders" \
  --arg cpuset "${cpuset:-not-requested}" --arg require_pinned "$require_pinned" \
  --argjson cycle_results "$cycle_results" \
  '($cycle_results | all(.[]; (.benchmark.status // .status) == "PASS")) as $all_cycles_passed |
   {schema_version:1,
    status:(if $all_cycles_passed and ($cycle_results | length) == $cycles
      then "PASSED" else "FAILED" end),
    generated_at_utc:$generated_at_utc,
    profile:"bounded-local-day-and-soak", binary:$binary, evidence_dir:$report_dir,
    workload:{book_count:150,warmup_iterations:$warmup,measured_iterations:$iterations,
      commands_per_cycle:(150 * $iterations * 2),events_per_cycle:(150 * $iterations * 2),
      cycles:$cycles,commands_total:(150 * $iterations * 2 * $cycles),
      maximum_resting_orders_per_book:$maximum_resting_orders},
    cpu:{requested_cpu_set:$cpuset,require_pinned:($require_pinned == "true")},
    cycle_reports:$cycle_results,
    claim_boundary:["bounded local native calibration and repeated deterministic checksums",
      "the default cycle represents the repository bounded local-day command envelope",
      "not a 24-hour wall-clock soak, Kafka end-to-end latency, or external certification"]}' \
  >"$report_dir/report.json"

printf 'Local depth certification passed: %s\n' "$report_dir/report.json"
