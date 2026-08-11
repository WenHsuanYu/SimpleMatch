#!/usr/bin/env bash
set -euo pipefail

benchmark_binary="${SIMPLEMATCH_MATCHING_BENCHMARK_BIN:-out/build/full-native-dev/simplematch-matching-capacity-benchmark}"
report_path="${SIMPLEMATCH_BENCHMARK_REPORT:-/tmp/simplematch-matching-capacity-$(date -u +%Y%m%dT%H%M%SZ).json}"
requested_cpu_set="${SIMPLEMATCH_BENCHMARK_CPUSET:-}"
require_pinned="${SIMPLEMATCH_REQUIRE_PINNED:-false}"

if [[ ! -x "${benchmark_binary}" ]]; then
  printf 'benchmark binary is missing or not executable: %s\n' "${benchmark_binary}" >&2
  printf 'build it with: cmake --build --preset full-native-dev --target simplematch-matching-capacity-benchmark\n' >&2
  exit 2
fi

if [[ "${require_pinned}" == true && -z "${requested_cpu_set}" ]]; then
  printf 'SIMPLEMATCH_REQUIRE_PINNED=true requires SIMPLEMATCH_BENCHMARK_CPUSET\n' >&2
  exit 2
fi

runner=("${benchmark_binary}")
if [[ -n "${requested_cpu_set}" ]]; then
  if ! command -v taskset >/dev/null 2>&1; then
    printf 'SIMPLEMATCH_BENCHMARK_CPUSET was supplied but taskset is unavailable\n' >&2
    exit 2
  fi
  runner=(taskset -c "${requested_cpu_set}" "${benchmark_binary}")
fi

temporary_output="$(mktemp /tmp/simplematch-matching-capacity.XXXXXX)"
cleanup() {
  rm -f "${temporary_output}"
}
trap cleanup EXIT

if ! "${runner[@]}" "$@" >"${temporary_output}"; then
  cat "${temporary_output}" >&2
  exit 1
fi

cpu_model="$(awk -F: '/model name/{gsub(/^ /, "", $2); print $2; exit}' /proc/cpuinfo 2>/dev/null || true)"
host_name="$(hostname 2>/dev/null || printf 'unknown')"
actual_cpu_count="$(getconf _NPROCESSORS_ONLN 2>/dev/null || printf 'unknown')"
requested_cpu_set="${requested_cpu_set:-not-requested}"

ruby -rjson -rtime - "${temporary_output}" "${report_path}" "${benchmark_binary}" \
  "${requested_cpu_set}" "${cpu_model}" "${host_name}" "${actual_cpu_count}" <<'RUBY'
benchmark_path, report_path, binary, cpu_set, cpu_model, host_name, cpu_count = ARGV
benchmark = JSON.parse(File.read(benchmark_path))
report = {
  "schema_version" => 1,
  "generated_at_utc" => Time.now.utc.iso8601,
  "binary" => binary,
  "host" => host_name,
  "cpu_model" => cpu_model,
  "online_cpu_count" => cpu_count,
  "requested_cpu_set" => cpu_set,
  "benchmark" => benchmark
}
File.write(report_path, JSON.pretty_generate(report) + "\n")
puts JSON.pretty_generate(report)
RUBY

printf 'report written to %s\n' "${report_path}" >&2
