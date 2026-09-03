#!/usr/bin/env bash

# Artifact adapter seam for incremental certification. The Planner calls this
# small interface; concrete image and Kafka modules own artifact-specific
# validation and materialization.

# The top-level certification runner owns this shared evidence root. Declare
# the contract here so shellcheck can verify this sourced adapter without
# weakening its diagnostics.
declare -g evidence_dir

certification_phase_cached_outputs_valid() {
  local phase_id="$1"
  local evidence_digest="$2"

  case "$phase_id" in
    local-image-build/*|registry-image-lock)
      certification_image_phase_cached_outputs_valid \
        "$phase_id" "$evidence_digest"
      ;;
    kafka-producer-contract)
      certification_kafka_phase_cached_outputs_valid \
        "$phase_id" "$evidence_digest"
      ;;
    *)
      return 0
      ;;
  esac
}

certification_phase_current_outputs_valid() {
  local phase_id="$1"
  local result_path="$2"

  case "$phase_id" in
    local-image-build/*|registry-image-lock)
      certification_image_phase_current_outputs_valid \
        "$phase_id" "$result_path"
      ;;
    kafka-producer-contract)
      certification_kafka_phase_current_outputs_valid \
        "$phase_id" "$result_path"
      ;;
    *)
      return 0
      ;;
  esac
}

certification_phase_revalidate() {
  local phase_id="$1"
  local evidence_digest="$2"

  case "$phase_id" in
    registry-publish/*)
      certification_image_phase_revalidate "$phase_id" "$evidence_digest"
      ;;
    *)
      return 1
      ;;
  esac
}

certification_phase_outputs_json() {
  local phase_id="$1"
  shift

  case "$phase_id" in
    local-image-build/*|registry-publish/*|registry-image-lock)
      certification_image_phase_outputs_json "$phase_id" "$@"
      ;;
    kafka-producer-contract)
      certification_kafka_phase_outputs_json "$phase_id" "$@"
      ;;
    kubernetes-cdc-delivery)
      certification_kubernetes_cdc_delivery_outputs_json
      ;;
    *)
      printf '%s\n' '[]'
      ;;
  esac
}

certification_kubernetes_cdc_delivery_outputs_json() {
  local file identity content_base64 path
  local -a files=(
    verdict.json
    event.json
    connector-running-before.json
    connectors-running-before.json
    connector-paused.json
    connector-recovered.json
    connectors-running-recovered.json
    health-liveness.json
    health-readiness.json
    metric-before-lag.json
    metric-before-age.json
    metric-baseline-age.txt
    metric-paused-lag.json
    metric-paused-age.json
    metric-recovered-lag.json
    metric-recovered-age.json
    metric-paused-row.txt
    metric-recovered-row.txt
    observation-row.txt
    account-service.log
    persistence.log
    market-data-projection.log
    marketdata-streamer.log
    query-service.log
    quickfix-gateway.log
    risk-service.log
  )

  for file in "${files[@]}"; do
    path="$evidence_dir/cdc-delivery/$file"
    [[ -f "$path" ]] || return 1
    identity="$(sha256sum "$path" | awk '{print "sha256:" $1}')" || return 1
    content_base64="$(base64 <"$path" | tr -d '\n')" || return 1
    jq -cn \
      --arg identity "$identity" \
      --arg name "risk-cdc-delivery-$file" \
      --arg location "cdc-delivery/$file" \
      --arg contentBase64 "$content_base64" \
      '{kind:"file-content",name:$name,identity:$identity,
        location:$location,contentBase64:$contentBase64}'
  done | jq -s .
}

certification_phase_materialize_reused_outputs() {
  local phase_id="$1"
  local evidence_digest="$2"

  case "$phase_id" in
    registry-publish/*|registry-image-lock)
      certification_image_phase_materialize_reused_outputs \
        "$phase_id" "$evidence_digest"
      ;;
    kafka-producer-contract)
      certification_kafka_phase_materialize_reused_outputs \
        "$phase_id" "$evidence_digest"
      ;;
    *)
      return 0
      ;;
  esac
}
