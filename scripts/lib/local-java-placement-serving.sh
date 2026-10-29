#!/usr/bin/env bash
# Stateful HTTP adapter for placement observations. Java owns the data verdict.
# shellcheck source=scripts/lib/local-resilience.sh
source "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/local-resilience.sh"

java_placement_serving_capture_http_probe() {
  local stage="$1" name="$2" path="$3"
  : "${evidence_dir:?evidence directory is required}" "${port_forward_port:?HTTP port is required}"
  local body_file="$evidence_dir/health/${stage}-${name}.json"
  local status module_dir request_seconds

  case "$name:$path" in
    readiness:/actuator/health/readiness|liveness:/actuator/health/liveness)
      status="$(curl --noproxy '*' --connect-timeout 2 \
        --max-time "$(remaining_seconds)" -sS -o "$body_file" -w '%{http_code}' \
        "http://127.0.0.1:${port_forward_port}${path}")" ||
        die "$stage $name request failed"
      ;;
    serving:/api/v1/freshness)
      module_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
      request_seconds="$(remaining_seconds)" || return 1
      if (( request_seconds > 20 )); then request_seconds=20; fi
      status="$(run_bounded python3 "$module_dir/../end-to-end/query-service/lib/query_http.py" \
        --fixture-directory "${query_fixture_directory:?approved Query fixture bundle is required}" \
        --base-url "http://127.0.0.1:${port_forward_port}" --path "$path" \
        --timeout-seconds "$request_seconds" --output "$body_file")" ||
        die "$stage serving request failed"
      ;;
    *) die 'unapproved placement HTTP probe'; return 1 ;;
  esac
  resilience_verify_evidence placement probe "$stage" "$name" "$status" "$body_file" ||
    die "$stage $name response failed its evidence contract"
}
