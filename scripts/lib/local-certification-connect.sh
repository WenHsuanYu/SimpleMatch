#!/usr/bin/env bash

# Local production-like Kafka Connect registration helpers.
# Sourced by run-local-production-like-certification.sh; shared run state is owned
# by the top-level orchestrator. This file defines behavior only and has no entry point.

register_kubernetes_outbox_connector() (
  local connector_name="${1:-}"
  local configmap_name="${2:-}"
  [[ -n "$connector_name" && -n "$configmap_name" ]] || {
    printf '%s\n' 'connector name and ConfigMap name are required.' >&2
    exit 1
  }

  local requested_forward_port="${SIMPLEMATCH_KAFKA_CONNECT_FORWARD_PORT:-}"
  local forward_port=""
  local connector_url=""
  local connector_json="$evidence_dir/${connector_name}-connector.json"
  local connector_config="$evidence_dir/${connector_name}-connector-config.json"
  local response_file="$evidence_dir/${connector_name}-connector-response.json"
  local update_response_file="$evidence_dir/${connector_name}-connector-update-response.json"
  local status_file="$evidence_dir/${connector_name}-status.json"
  local connectors_file="$evidence_dir/kafka-connect-connectors.json"
  local port_forward_log="$evidence_dir/kafka-connect-${connector_name}-port-forward.log"
  local port_forward_pid
  local status_code
  local provider_retry_count=0
  local provider_retry_limit="${SIMPLEMATCH_KAFKA_CONNECT_PROVIDER_RETRIES:-90}"
  local connect_rest_ready=false
  local -a curl_options=(--connect-timeout 5 --max-time 15)

  request_to_file() {
    local method="$1"
    local path="$2"
    local output_file="$3"
    local payload_file="${4:-}"
    local -a request=(curl "${curl_options[@]}" -sS -o "$output_file" -w '%{http_code}' -X "$method")

    if [[ -n "$payload_file" ]]; then
      request+=(-H 'Content-Type: application/json' --data-binary "@$payload_file")
    fi
    "${request[@]}" "${connector_url}${path}"
  }

  print_evidence() {
    local label="$1"
    local path="$2"
    printf '\n=== %s ===\n' "$label" >&2
    if [[ ! -s "$path" ]]; then
      printf '(empty)\n' >&2
      return 0
    fi
    jq . "$path" >&2 2>/dev/null || cat "$path" >&2
    printf '\n' >&2
  }

  dump_connect_diagnostics() {
    request_to_file GET /connectors "$connectors_file" >/dev/null 2>&1 || true
    request_to_file GET "/connectors/${connector_name}/status" "$status_file" \
      >/dev/null 2>&1 || true
    print_evidence 'Kafka Connect connectors' "$connectors_file"
    print_evidence "${connector_name} registration response" "$response_file"
    print_evidence "${connector_name} update response" "$update_response_file"
    print_evidence "${connector_name} status" "$status_file"
    printf '\n=== Kafka Connect port-forward ===\n' >&2
    cat "$port_forward_log" >&2 || true
  }

  status_response_is_valid() {
    jq -e '
      (.connector.state | type == "string")
      and (.tasks | type == "array")
    ' "$status_file" >/dev/null 2>&1
  }

  status_response_has_failure() {
    jq -e '
      (.connector.state == "FAILED")
      or any(.tasks[]?; .state == "FAILED")
    ' "$status_file" >/dev/null 2>&1
  }

  status_response_is_running() {
    jq -e '
      .connector.state == "RUNNING"
      and (.tasks | length > 0)
      and all(.tasks[]; .state == "RUNNING")
    ' "$status_file" >/dev/null 2>&1
  }

  fail_with_diagnostics() {
    printf '%s\n' "$1" >&2
    dump_connect_diagnostics
    exit 1
  }

  [[ "$provider_retry_limit" =~ ^[1-9][0-9]*$ ]] || {
    printf 'SIMPLEMATCH_KAFKA_CONNECT_PROVIDER_RETRIES must be a positive integer.\n' >&2
    exit 1
  }

  kubectl -n "$namespace" rollout status deployment/kafka-connect --timeout=300s
  kubectl -n "$namespace" get configmap "$configmap_name" \
    -o jsonpath='{.data.connector\.json}' >"$connector_json"
  jq -e --arg connector "$connector_name" \
    '.name == $connector and (.config | type == "object")' "$connector_json" >/dev/null

  local forward_spec="${requested_forward_port}:8083"
  kubectl -n "$namespace" port-forward service/kafka-connect "$forward_spec" \
    >"$port_forward_log" 2>&1 &
  port_forward_pid=$!
  stop_port_forward() {
    kill "$port_forward_pid" >/dev/null 2>&1 || true
    wait "$port_forward_pid" >/dev/null 2>&1 || true
  }
  trap stop_port_forward EXIT

  for _ in $(seq 1 30); do
    check_certification_deadline
    if [[ -s "$port_forward_log" ]]; then
      if grep -Eq 'Unable to listen|error: unable to listen|address already in use' \
        "$port_forward_log"; then
        cat "$port_forward_log" >&2
        exit 1
      fi
      forward_port="$(sed -nE 's/.*Forwarding from 127\.0\.0\.1:([0-9]+).*/\1/p' \
        "$port_forward_log" | tail -1)"
      if [[ -n "$forward_port" ]]; then
        break
      fi
    fi
    if ! kill -0 "$port_forward_pid" >/dev/null 2>&1; then
      cat "$port_forward_log" >&2
      exit 1
    fi
    sleep 1
  done
  [[ -n "$forward_port" ]] || {
    cat "$port_forward_log" >&2
    exit 1
  }
  connector_url="http://127.0.0.1:${forward_port}"

  for _ in $(seq 1 90); do
    check_certification_deadline
    if status_code="$(request_to_file GET /connectors "$connectors_file" 2>/dev/null)" \
      && [[ "$status_code" == 2?? ]]; then
      connect_rest_ready=true
      break
    fi
    if ! kill -0 "$port_forward_pid" >/dev/null 2>&1; then
      cat "$port_forward_log" >&2
      exit 1
    fi
    sleep 2
  done
  if [[ "$connect_rest_ready" != true ]]; then
    fail_with_diagnostics 'Kafka Connect REST endpoint did not become ready before registration.'
  fi

  while true; do
    check_certification_deadline
    if ! status_code="$(request_to_file POST /connectors "$response_file" "$connector_json")"; then
      fail_with_diagnostics 'Kafka Connect REST registration request failed before receiving a response.'
    fi
    case "$status_code" in
      2??)
        break
        ;;
      409)
        jq -c '.config' "$connector_json" >"$connector_config"
        if ! status_code="$(request_to_file PUT "/connectors/${connector_name}/config" \
          "$update_response_file" "$connector_config")"; then
          fail_with_diagnostics 'Kafka Connect REST update request failed before receiving a response.'
        fi
        [[ "$status_code" == 2?? ]] || fail_with_diagnostics \
          "Kafka Connect rejected ${connector_name} update with HTTP ${status_code}."
        break
        ;;
      400)
        if ! jq -e '
          (.message // "")
          | test("\\$\\{envvarprovider:[A-Za-z0-9_]+\\}")
        ' "$response_file" >/dev/null 2>&1; then
          fail_with_diagnostics \
            "Kafka Connect rejected ${connector_name} registration with HTTP 400."
        fi
        provider_retry_count=$((provider_retry_count + 1))
        if (( provider_retry_count >= provider_retry_limit )); then
          fail_with_diagnostics \
            "Kafka Connect EnvVarConfigProvider remained unavailable after ${provider_retry_limit} attempts."
        fi
        printf 'Kafka Connect EnvVarConfigProvider is not ready; retrying registration (%d/%d).\n' \
          "$provider_retry_count" "$provider_retry_limit" >&2
        sleep 2
        ;;
      *)
        fail_with_diagnostics \
          "Kafka Connect rejected ${connector_name} registration with HTTP ${status_code}."
        ;;
    esac
  done

  for _ in $(seq 1 90); do
    check_certification_deadline
    if ! status_code="$(request_to_file GET \
      "/connectors/${connector_name}/status" "$status_file")"; then
      fail_with_diagnostics 'Kafka Connect status request failed before receiving a response.'
    fi

    case "$status_code" in
      200)
        status_response_is_valid || fail_with_diagnostics \
          "Kafka Connect returned a malformed ${connector_name} status response."
        status_response_has_failure && fail_with_diagnostics \
          "${connector_name} entered FAILED state."
        if status_response_is_running; then
          exit 0
        fi
        ;;
      404)
        # A newly registered connector can be briefly absent from the REST view while the
        # distributed workers publish and consume the config update. Treat this as transient.
        ;;
      *)
        fail_with_diagnostics \
          "Kafka Connect returned unexpected HTTP ${status_code} for ${connector_name} status."
        ;;
    esac
    sleep 2
  done

  fail_with_diagnostics \
    "${connector_name} did not reach RUNNING state before the status deadline."
)

register_kubernetes_risk_connector() {
  register_kubernetes_outbox_connector risk-service-outbox risk-service-outbox-connector
}

register_kubernetes_account_connector() {
  register_kubernetes_outbox_connector account-service-outbox account-service-outbox-connector
}

register_kubernetes_marketdata_connector() {
  register_kubernetes_outbox_connector \
    marketdata-publisher-outbox marketdata-publisher-outbox-connector
}
