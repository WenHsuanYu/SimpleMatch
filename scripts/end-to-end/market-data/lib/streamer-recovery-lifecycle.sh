#!/usr/bin/env bash

# Lifecycle seams for the market-data streamer recovery certification.

projection_port_forward_log_path() {
  local evidence_root="$1"
  local phase="$2"
  case "$phase" in
    setup)
      printf '%s/diagnostics/projection-port-forward-setup.log\n' "$evidence_root"
      ;;
    initial|resubscribed)
      printf '%s/diagnostics/projection-port-forward-%s-replay.log\n' \
        "$evidence_root" "$phase"
      ;;
    *)
      return 1
      ;;
  esac
}

start_projection_port_forward() {
  local evidence_root="$1"
  local phase="$2"
  local log_path
  log_path="$(projection_port_forward_log_path "$evidence_root" "$phase")" || return 1
  start_port_forward service/market-data-projection 8080 \
    "$log_path" projection_port_forward_pid projection_port
}

streamer_port_forward_log_path() {
  local evidence_root="$1"
  local phase="$2"
  case "$phase" in
    initial)
      printf '%s/diagnostics/streamer-port-forward-initial.log\n' "$evidence_root"
      ;;
    replacement)
      printf '%s/diagnostics/streamer-port-forward-replacement.log\n' "$evidence_root"
      ;;
    *)
      return 1
      ;;
  esac
}

start_streamer_port_forward() {
  local evidence_root="$1"
  local phase="$2"
  local requested_port="${3:-}"
  local log_path
  log_path="$(streamer_port_forward_log_path "$evidence_root" "$phase")" || return 1
  start_port_forward service/marketdata-streamer 50053 \
    "$log_path" streamer_port_forward_pid streamer_port "$requested_port"
}

refresh_streamer_port_forward() {
  local evidence_root="$1"
  local existing_port="${streamer_port:-}"
  [[ "$existing_port" =~ ^[0-9]+$ ]] ||
    die 'streamer port-forward local port is unavailable'
  stop_streamer_port_forward
  start_streamer_port_forward "$evidence_root" replacement "$existing_port" ||
    die 'market-data streamer replacement port-forward did not become ready'
}

publish_streamer_replacement_ready() {
  local evidence_root="$1"
  refresh_streamer_port_forward "$evidence_root"
  : >"$evidence_root/signals/replacement.ready"
}

produce_projection_snapshot() {
  local evidence_root="$1"
  local phase="$2"
  reset_projection_state "$phase" || die "$phase projection reset failed"
  reset_projection_offsets "$phase" || die "$phase projection offset reset failed"
  kns rollout restart deployment/market-data-projection >/dev/null
  wait_deployment_replicas market-data-projection 1 ||
    die "$phase projection replay did not become ready"
  stop_projection_port_forward
  start_projection_port_forward "$evidence_root" "$phase" ||
    die "$phase projection management port-forward did not become ready"
}
