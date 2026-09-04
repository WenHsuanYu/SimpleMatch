#!/usr/bin/env bash

# Local production-like workload selection and verification helpers.
# Sourced by run-local-production-like-certification.sh; shared run state is owned
# by the top-level orchestrator. This file defines behavior only and has no entry point.

select_matching_workload() {
  local workload_manifest="$1"
  local matching_manifest="$2"

  ruby -ryaml -e '
    documents = YAML.load_stream(File.read(ARGV.fetch(0))).compact
    matching = documents.select do |document|
      document.fetch("kind") == "StatefulSet" && document.dig("metadata", "name") == "matching"
    end
    abort "rendered local workload manifest must contain exactly one Matching StatefulSet" unless matching.length == 1
    File.write(ARGV.fetch(1), YAML.dump_stream(*matching))
  ' "$workload_manifest" "$matching_manifest"
}

wait_for_kubernetes_workloads() {
  local workload
  for workload in account-service risk-service persistence market-data-projection marketdata-streamer query-service; do
    check_certification_deadline
    kubectl -n "$namespace" rollout status "deployment/${workload}" --timeout=300s
  done
  check_certification_deadline
  kubectl -n "$namespace" rollout status statefulset/matching --timeout=600s
  check_certification_deadline
  kubectl -n "$namespace" rollout status statefulset/quickfix-gateway --timeout=300s
}

wait_for_local_matching_fleet() {
  check_certification_deadline
  kubectl -n "$namespace" rollout status statefulset/matching --timeout=600s
}

verify_local_matching_fleet() {
  local args=(
    --namespace "$namespace"
    --allow-shared-node
  )
  if [[ "$image_transport" == kind-load ]]; then
    args+=(--allow-local-image "$matching_image_reference")
  fi
  bash "$repo_root/scripts/verify-matching-fleet-live.sh" "${args[@]}"
}
