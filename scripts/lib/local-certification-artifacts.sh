#!/usr/bin/env bash

# Artifact adapter seam for incremental certification. The Planner calls this
# small interface; concrete image and Kafka modules own artifact-specific
# validation and materialization.

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
    *)
      printf '%s\n' '[]'
      ;;
  esac
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
