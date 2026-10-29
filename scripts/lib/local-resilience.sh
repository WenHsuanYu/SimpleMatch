#!/usr/bin/env bash

set -euo pipefail

: "${SIMPLEMATCH_KIND_KUBECTL_BIN:=kubectl}"

# Image-cache preflight is a resilience precondition, not kind lifecycle
# management. Its single public function keeps deployment diagnostics from
# widening the generic kind helper's runtime provenance scope.

: "${SIMPLEMATCH_KIND_IMAGE_CACHE_PREFLIGHT_DEFAULT_SECONDS:=60}"
: "${SIMPLEMATCH_KIND_IMAGE_CACHE_PREFLIGHT_MAX_SECONDS:=120}"

_simplematch_kind_image_cache_remaining() {
  local deadline_at="$1"
  local remaining=$((deadline_at - SECONDS))

  (( remaining > 0 )) || return 124
  printf '%s\n' "$remaining"
}

_simplematch_kind_image_cache_run() {
  local deadline_at="$1"
  shift
  local remaining

  remaining="$(_simplematch_kind_image_cache_remaining "$deadline_at")" || return 124
  timeout --foreground "${remaining}s" "$@"
}

# Verify the deployed workload image against every eligible kind node before
# it can trigger a cold pull. The check deliberately does not pull: a missing
# or unexecutable image is a precondition failure, not a recovery event. The
# evidence file is written for both PASS and FAIL so a caller can explain the
# exact node-level failure without mutating the cluster.
simplematch_kind_image_cache_preflight() {
  local context="$1"
  local workload_file="$2"
  local evidence_file="$3"
  local budget_seconds="$SIMPLEMATCH_KIND_IMAGE_CACHE_PREFLIGHT_DEFAULT_SECONDS"
  local started_at completed_at deadline_at nodes_json image_reference node_selector
  local expected_identity="" identity_source="node-containerd" reference_digest=""
  local status=PASS failure_reason="" results='[]'
  local node inspect_output identity probe_name node_status reason
  local inspect_status probe_status
  local -a nodes=()

  [[ -n "$context" && -s "$workload_file" && -n "$evidence_file" ]] || return 1
  [[ "$budget_seconds" =~ ^[1-9][0-9]*$ &&
    "$budget_seconds" -le "$SIMPLEMATCH_KIND_IMAGE_CACHE_PREFLIGHT_MAX_SECONDS" ]] || return 1
  command -v "$SIMPLEMATCH_KIND_KUBECTL_BIN" >/dev/null 2>&1 || return 1
  command -v docker >/dev/null 2>&1 || return 1
  command -v jq >/dev/null 2>&1 || return 1
  command -v timeout >/dev/null 2>&1 || return 1

  mkdir -p "$(dirname -- "$evidence_file")" || return 1
  started_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)" || return 1
  deadline_at=$((SECONDS + budget_seconds))
  image_reference="$(jq -er '
    [.spec.template.spec.containers[]? | select(.name == "kafka-connect") | .image]
    | if length == 1 and (.[0] | type == "string" and length > 0) then .[0]
      else empty end
  ' "$workload_file")" || {
    status=FAILED
    failure_reason='Connect workload does not identify exactly one runtime image'
  }
  [[ "$image_reference" != *[[:space:]]* ]] || {
    status=FAILED
    failure_reason='Connect workload image reference contains whitespace'
  }
  node_selector="$(jq -c '.spec.template.spec.nodeSelector // {}' "$workload_file")" || {
    status=FAILED
    failure_reason='Connect workload node selector is malformed'
  }
  if [[ "$image_reference" == *@sha256:* ]]; then
    reference_digest="${image_reference##*@}"
    [[ "$reference_digest" =~ ^sha256:[0-9a-f]{64}$ ]] || {
      failure_reason='digest-pinned image reference is not canonical'
      status=FAILED
    }
  fi

  if [[ "$status" == PASS ]]; then
    if ! nodes_json="$(_simplematch_kind_image_cache_run "$deadline_at" \
        "$SIMPLEMATCH_KIND_KUBECTL_BIN" --context "$context" get nodes -o json 2>&1)"; then
      status=FAILED
      failure_reason='could not read eligible kind nodes before the image-cache deadline'
    elif ! jq -e '.items | type == "array"' <<<"$nodes_json" >/dev/null 2>&1; then
      status=FAILED
      failure_reason='kind node evidence is malformed'
    else
      mapfile -t nodes < <(jq -r --argjson selector "$node_selector" '
        .items[]
        | select(.spec.unschedulable != true)
        | select(([.spec.taints[]?.effect] | index("NoSchedule")) == null)
        | select(any(.status.conditions[]?; .type == "Ready" and .status == "True"))
        | select(. as $node
            | ($selector | to_entries | all(.[];
                ($node.metadata.labels[.key] // null) == .value)))
        | .metadata.name
      ' <<<"$nodes_json")
      if ((${#nodes[@]} == 0)); then
        status=FAILED
        failure_reason='no schedulable kind worker is available for the image-cache preflight'
      fi
    fi
  fi

  if [[ "$status" == PASS ]]; then
    for node in "${nodes[@]}"; do
      node_status=PASS
      reason=''
      identity=''
      inspect_status=FAILED
      probe_status=NOT_RUN
      inspect_output=''
      if inspect_output="$(_simplematch_kind_image_cache_run "$deadline_at" \
          docker exec "$node" crictl inspecti "$image_reference" 2>&1)" &&
        identity="$(jq -er '.status.id | select(type == "string" and test("^sha256:[0-9a-f]{64}$"))' \
          <<<"$inspect_output" 2>/dev/null)"; then
        inspect_status=PASS
      else
        node_status=FAILED
        reason='image is not present with a canonical identity in node containerd'
      fi

      if [[ "$node_status" == PASS && -n "$expected_identity" &&
        "$identity" != "$expected_identity" ]]; then
        node_status=FAILED
        reason="image identity differs from the expected $expected_identity"
      elif [[ "$node_status" == PASS && -z "$expected_identity" ]]; then
        expected_identity="$identity"
      fi

      if [[ "$node_status" == PASS ]]; then
        probe_name="simplematch-image-cache-probe-${RANDOM}-${BASHPID}"
        if _simplematch_kind_image_cache_run "$deadline_at" \
            docker exec "$node" ctr -n k8s.io run --rm --net-host \
            "$image_reference" "$probe_name" /bin/sh -c true \
            >/dev/null 2>&1; then
          probe_status=PASS
        else
          node_status=FAILED
          probe_status=FAILED
          reason='image metadata exists but containerd execution probe failed'
        fi
      fi

      results="$(jq -c --arg node "$node" --arg status "$node_status" \
        --arg inspect "$inspect_status" --arg probe "$probe_status" \
        --arg identity "$identity" --arg reason "$reason" \
        '. + [{node:$node,status:$status,inspect_status:$inspect,
          execution_probe_status:$probe,identity:$identity,
          failure_reason:(if $reason == "" then null else $reason end)}]' \
        <<<"$results")" || return 1
      [[ "$node_status" == PASS ]] || status=FAILED

      if ! _simplematch_kind_image_cache_remaining "$deadline_at" >/dev/null; then
        status=FAILED
        failure_reason='image-cache preflight deadline elapsed before every eligible node was checked'
        break
      fi
    done
  fi

  if [[ "$status" == FAILED && -z "$failure_reason" ]]; then
    failure_reason='one or more eligible kind nodes failed the image-cache preflight'
  fi
  completed_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)" || return 1
  if [[ "$status" == PASS && -z "$expected_identity" ]]; then
    status=FAILED
    failure_reason='image-cache preflight did not observe an image identity'
  fi
  jq -n --arg status "$status" --arg context "$context" \
    --arg image "$image_reference" --arg identity "$expected_identity" \
    --arg source "$identity_source" --arg started "$started_at" \
    --arg completed "$completed_at" --argjson budget "$budget_seconds" \
    --argjson nodes "$results" --arg reason "$failure_reason" \
    '{schema_version:1,status:$status,context:$context,
      image_reference:$image,image_identity:(if $identity == "" then null else $identity end),
      identity_source:$source,budget_seconds:$budget,
      started_at_utc:$started,completed_at_utc:$completed,nodes:$nodes,
      failure_reason:(if $reason == "" then null else $reason end)}' \
    >"$evidence_file" || return 1
  [[ "$status" == PASS ]]
}

resilience_log_is_safe() {
  local path="$1" grep_status
  [[ -f "$path" ]] || return 1
  if grep -Eiq '8=FIX|(^|[^[:alnum:]_])(35|49|56|55|54|38|44)=[^[:space:]]+|password[=:]|secret[=:]|credentials?[=:]|raw[_ -]?fix|complete[_ -]?account[_ -]?payload|"?(password|secret|credentials?|token|access[_ -]?token|refresh[_ -]?token|private[_ -]?key|client[_ -]?secret)"?[[:space:]]*:[[:space:]]*"?[^"[:space:],}]+|Bearer[[:space:]]+[A-Za-z0-9._~+/-]+=*' "$path"; then
    return 1
  else
    grep_status=$?
  fi
  [[ "$grep_status" -eq 1 ]]
}

# Prepare deterministic Java work before a diagnostic can inject a fault.
resilience_prepare_evidence_verifier() {
  local repo_root="$1"
  timeout --foreground 120 "$repo_root/gradlew" -q -p "$repo_root" \
    :tools:recovery-evidence-verifier:installDist || return 1
  RESILIENCE_EVIDENCE_VERIFIER_EXECUTABLE="$repo_root/tools/recovery-evidence-verifier/build/install/recovery-evidence-verifier/bin/recovery-evidence-verifier"
  [[ -x "$RESILIENCE_EVIDENCE_VERIFIER_EXECUTABLE" ]]
}

resilience_verify_evidence() {
  [[ -x "${RESILIENCE_EVIDENCE_VERIFIER_EXECUTABLE:-}" ]] || return 1
  "$RESILIENCE_EVIDENCE_VERIFIER_EXECUTABLE" "$@"
}
