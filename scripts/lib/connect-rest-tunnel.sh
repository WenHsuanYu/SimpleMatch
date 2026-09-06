#!/usr/bin/env bash

set -euo pipefail

# Kafka Connect REST tunnel Module. Callers configure the Kubernetes target,
# request connector status, and close the tunnel. Process ownership, log
# offsets, local ports, retries, and teardown stay inside this implementation.

: "${CONNECT_REST_TUNNEL_KUBECTL_BIN:=kubectl}"
: "${CONNECT_REST_TUNNEL_CURL_BIN:=curl}"

CONNECT_REST_TUNNEL_CONTEXT=""
CONNECT_REST_TUNNEL_NAMESPACE=""
CONNECT_REST_TUNNEL_LOG_PATH=""
CONNECT_REST_TUNNEL_PID=""
CONNECT_REST_TUNNEL_URL=""

_connect_rest_tunnel_port() {
  local log_path="$1" byte_offset="$2" target_port="$3" port

  [[ -r "$log_path" && "$byte_offset" =~ ^[0-9]+$ &&
    "$target_port" =~ ^[0-9]+$ ]] || return 1
  port="$(tail -c +$((byte_offset + 1)) "$log_path" |
    sed -nE "s/^Forwarding from 127\\.0\\.0\\.1:([0-9]+) -> ${target_port}$/\\1/p" |
    tail -n 1)"
  [[ "$port" =~ ^[1-9][0-9]{0,4}$ ]] && ((port <= 65535)) || return 1
  printf '%s\n' "$port"
}

connect_rest_tunnel_configure() {
  local context="$1" namespace="$2" log_path="$3"

  [[ -n "$context" && -n "$namespace" && -n "$log_path" ]] || return 2
  CONNECT_REST_TUNNEL_CONTEXT="$context"
  CONNECT_REST_TUNNEL_NAMESPACE="$namespace"
  CONNECT_REST_TUNNEL_LOG_PATH="$log_path"
}

connect_rest_tunnel_close() {
  local budget_seconds="${1:-5}" deadline iteration pid

  [[ "$budget_seconds" =~ ^[1-9][0-9]*$ ]] || return 2
  [[ -n "$CONNECT_REST_TUNNEL_PID" ]] || return 0
  pid="$CONNECT_REST_TUNNEL_PID"
  deadline=$((SECONDS + budget_seconds))
  kill "$pid" >/dev/null 2>&1 || true
  for ((iteration = 0; iteration < 50; iteration++)); do
    if ! kill -0 "$pid" >/dev/null 2>&1; then
      wait "$pid" >/dev/null 2>&1 || true
      CONNECT_REST_TUNNEL_PID=""
      CONNECT_REST_TUNNEL_URL=""
      return 0
    fi
    ((SECONDS < deadline)) || break
    sleep 0.1
  done
  kill -KILL "$pid" >/dev/null 2>&1 || true
  wait "$pid" >/dev/null 2>&1 || true
  CONNECT_REST_TUNNEL_PID=""
  CONNECT_REST_TUNNEL_URL=""
  ((SECONDS < deadline))
}

_connect_rest_tunnel_open() {
  local budget_seconds="$1" deadline log_offset port

  [[ "$budget_seconds" =~ ^[1-9][0-9]*$ ]] || return 2
  [[ -n "$CONNECT_REST_TUNNEL_CONTEXT" &&
    -n "$CONNECT_REST_TUNNEL_NAMESPACE" &&
    -n "$CONNECT_REST_TUNNEL_LOG_PATH" ]] || return 2
  connect_rest_tunnel_close "$budget_seconds" || return 1
  if [[ -e "$CONNECT_REST_TUNNEL_LOG_PATH" ||
    -L "$CONNECT_REST_TUNNEL_LOG_PATH" ]]; then
    [[ -f "$CONNECT_REST_TUNNEL_LOG_PATH" &&
      ! -L "$CONNECT_REST_TUNNEL_LOG_PATH" ]] || return 1
    log_offset="$(wc -c <"$CONNECT_REST_TUNNEL_LOG_PATH")" || return 1
  else
    log_offset=0
  fi
  printf '%s\n' 'Starting Kafka Connect service port-forward' \
    >>"$CONNECT_REST_TUNNEL_LOG_PATH"
  "$CONNECT_REST_TUNNEL_KUBECTL_BIN" --context "$CONNECT_REST_TUNNEL_CONTEXT" \
    -n "$CONNECT_REST_TUNNEL_NAMESPACE" port-forward service/kafka-connect :8083 \
    >>"$CONNECT_REST_TUNNEL_LOG_PATH" 2>&1 &
  CONNECT_REST_TUNNEL_PID="$!"
  deadline=$((SECONDS + budget_seconds))
  while ((SECONDS < deadline)); do
    kill -0 "$CONNECT_REST_TUNNEL_PID" >/dev/null 2>&1 || return 1
    if port="$(_connect_rest_tunnel_port \
      "$CONNECT_REST_TUNNEL_LOG_PATH" "$log_offset" 8083)"; then
      CONNECT_REST_TUNNEL_URL="http://127.0.0.1:${port}"
      return 0
    fi
    sleep 0.1
  done
  return 1
}

_connect_rest_tunnel_request() {
  local connector="$1" budget_seconds="$2" request_seconds

  request_seconds="$budget_seconds"
  ((request_seconds > 5)) && request_seconds=5
  timeout --foreground "${budget_seconds}s" "$CONNECT_REST_TUNNEL_CURL_BIN" \
    -fsS --connect-timeout 2 --max-time "$request_seconds" \
    "$CONNECT_REST_TUNNEL_URL/connectors/$connector/status"
}

connect_rest_tunnel_status() {
  local connector="$1" budget_seconds="$2"

  [[ "$connector" =~ ^[A-Za-z0-9._-]+$ &&
    "$budget_seconds" =~ ^[1-9][0-9]*$ ]] || return 2
  if [[ -z "$CONNECT_REST_TUNNEL_PID" ]] ||
    ! kill -0 "$CONNECT_REST_TUNNEL_PID" >/dev/null 2>&1; then
    _connect_rest_tunnel_open "$budget_seconds" || return 1
  fi
  _connect_rest_tunnel_request "$connector" "$budget_seconds" && return 0
  printf '%s\n' "Restarting Kafka Connect service port-forward after REST failure" \
    >>"$CONNECT_REST_TUNNEL_LOG_PATH"
  _connect_rest_tunnel_open "$budget_seconds" || return 1
  _connect_rest_tunnel_request "$connector" "$budget_seconds"
}
