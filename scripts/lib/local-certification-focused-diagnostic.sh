#!/usr/bin/env bash

# Focused diagnostics for one retained certification phase. The caller supplies
# only the evidence directory and timeout; this module derives the runtime
# identity from the retained run-context and never changes certification plan
# or phase evidence.

declare -gA SIMPLEMATCH_FOCUSED_CONTEXT=()
declare -ga SIMPLEMATCH_FOCUSED_DEPENDENCIES=()
declare -g SIMPLEMATCH_FOCUSED_FAILURE_REASON=""
declare -g SIMPLEMATCH_FOCUSED_KIND_CONTEXT=""
declare -g SIMPLEMATCH_FOCUSED_SOURCE_SIGNATURE=""
declare -g SIMPLEMATCH_FOCUSED_RETAINED_CDC_RUNTIME_SIGNATURE=""
declare -g SIMPLEMATCH_FOCUSED_RETAINED_CDC_VERIFIER_SIGNATURE=""
declare -g SIMPLEMATCH_FOCUSED_CURRENT_CDC_RUNTIME_SIGNATURE=""
declare -g SIMPLEMATCH_FOCUSED_CURRENT_CDC_VERIFIER_SIGNATURE=""
declare -g SIMPLEMATCH_FOCUSED_VERIFIER_CHANGED=false
declare -g SIMPLEMATCH_FOCUSED_CURRENT_REVISION=""
declare -g SIMPLEMATCH_FOCUSED_IMAGE_LOCK_DIGEST=""

# These values are supplied by the entry point. Defaults keep the sourced
# module safe for contract tests and make every external dependency explicit.
: "${focused_evidence_dir:=}"
: "${focused_repo_root:=}"
: "${focused_kubectl_bin:=kubectl}"
: "${focused_preflight_deadline_epoch:=0}"
: "${focused_image_transport:=}"
: "${focused_image_lock:=}"
: "${focused_verifier_contract_script:=}"
: "${focused_verifier_contract_output:=}"

simplematch_focused_failure_reason() {
  printf '%s\n' "$SIMPLEMATCH_FOCUSED_FAILURE_REASON"
}

simplematch_focused_source_signature() {
  printf '%s\n' "$SIMPLEMATCH_FOCUSED_SOURCE_SIGNATURE"
}

simplematch_focused_current_cdc_runtime_signature() {
  printf '%s\n' "$SIMPLEMATCH_FOCUSED_CURRENT_CDC_RUNTIME_SIGNATURE"
}

simplematch_focused_current_cdc_verifier_signature() {
  printf '%s\n' "$SIMPLEMATCH_FOCUSED_CURRENT_CDC_VERIFIER_SIGNATURE"
}

simplematch_focused_verifier_changed() {
  printf '%s\n' "$SIMPLEMATCH_FOCUSED_VERIFIER_CHANGED"
}

simplematch_focused_fail() {
  SIMPLEMATCH_FOCUSED_FAILURE_REASON="$1"
  printf 'Focused CDC diagnostic: %s\n' "$1" >&2
  return 1
}

simplematch_focused_context_value() {
  local key="$1"
  [[ -n "${SIMPLEMATCH_FOCUSED_CONTEXT[$key]+x}" ]] || return 1
  printf '%s\n' "${SIMPLEMATCH_FOCUSED_CONTEXT[$key]}"
}

simplematch_focused_load_context() {
  local context_file="$1"
  local line key value
  local -A seen=()

  [[ -f "$context_file" ]] || simplematch_focused_fail \
    "retained run context is missing: $context_file" || return 1
  SIMPLEMATCH_FOCUSED_CONTEXT=()
  while IFS= read -r line || [[ -n "$line" ]]; do
    [[ "$line" == *=* ]] || simplematch_focused_fail \
      'retained run context contains a malformed line' || return 1
    key="${line%%=*}"
    value="${line#*=}"
    case "$key" in
      run_id|namespace|cluster|trading_day|image_tag|image_transport|source_signature|\
      cdc_runtime_signature|cdc_verifier_signature|\
      skip_build|skip_compose|skip_kubernetes|matching_fleet_only)
        ;;
      *)
        simplematch_focused_fail "retained run context contains unknown key: $key" ||
          return 1
        ;;
    esac
    [[ -z "${seen[$key]+x}" ]] || simplematch_focused_fail \
      "retained run context repeats key: $key" || return 1
    [[ -n "$value" && "$value" != *[[:space:]]* ]] || \
      simplematch_focused_fail "retained run context has an invalid value for $key" ||
      return 1
    seen["$key"]=true
    SIMPLEMATCH_FOCUSED_CONTEXT["$key"]="$value"
  done <"$context_file"

  for key in \
      run_id namespace cluster trading_day image_tag image_transport source_signature \
      cdc_runtime_signature cdc_verifier_signature \
      skip_build skip_compose skip_kubernetes matching_fleet_only; do
    [[ -n "${SIMPLEMATCH_FOCUSED_CONTEXT[$key]+x}" ]] || \
      simplematch_focused_fail "retained run context is missing $key" || return 1
  done
}

simplematch_focused_validate_context() {
  local run_id namespace cluster trading_day image_tag image_transport source_signature
  local cdc_runtime_signature cdc_verifier_signature
  local skip_build skip_compose skip_kubernetes matching_fleet_only

  run_id="$(simplematch_focused_context_value run_id)"
  namespace="$(simplematch_focused_context_value namespace)"
  cluster="$(simplematch_focused_context_value cluster)"
  trading_day="$(simplematch_focused_context_value trading_day)"
  image_tag="$(simplematch_focused_context_value image_tag)"
  image_transport="$(simplematch_focused_context_value image_transport)"
  source_signature="$(simplematch_focused_context_value source_signature)"
  cdc_runtime_signature="$(simplematch_focused_context_value cdc_runtime_signature)"
  cdc_verifier_signature="$(simplematch_focused_context_value cdc_verifier_signature)"
  skip_build="$(simplematch_focused_context_value skip_build)"
  skip_compose="$(simplematch_focused_context_value skip_compose)"
  skip_kubernetes="$(simplematch_focused_context_value skip_kubernetes)"
  matching_fleet_only="$(simplematch_focused_context_value matching_fleet_only)"

  [[ "$run_id" =~ ^[0-9]{8}-[0-9]{6}-[0-9]+$ ]] || \
    simplematch_focused_fail "invalid retained run identity: $run_id" || return 1
  [[ ${#namespace} -le 63 && "$namespace" =~ ^[a-z0-9]([-a-z0-9]*[a-z0-9])?$ ]] || \
    simplematch_focused_fail "invalid retained namespace: $namespace" || return 1
  [[ "$cluster" =~ ^[a-z0-9]([-a-z0-9]*[a-z0-9])?$ ]] || \
    simplematch_focused_fail "invalid retained kind cluster: $cluster" || return 1
  [[ "$trading_day" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}$ ]] || \
    simplematch_focused_fail "invalid retained trading day: $trading_day" || return 1
  simplematch_local_image_tag_validate "$image_tag" || \
    simplematch_focused_fail "invalid retained image tag: $image_tag" || return 1
  simplematch_local_image_transport_validate "$image_transport" || \
    simplematch_focused_fail "invalid retained image transport: $image_transport" || return 1
  [[ "$source_signature" =~ ^[0-9a-f]{64}$ ]] || \
    simplematch_focused_fail 'retained source signature is not a canonical SHA-256' ||
    return 1
  [[ "$cdc_runtime_signature" =~ ^[0-9a-f]{64}$ ]] || \
    simplematch_focused_fail \
      'retained CDC runtime signature is not a canonical SHA-256; create a fresh full run' ||
    return 1
  [[ "$cdc_verifier_signature" =~ ^[0-9a-f]{64}$ ]] || \
    simplematch_focused_fail \
      'retained CDC verifier signature is not a canonical SHA-256; create a fresh full run' ||
    return 1
  [[ "$skip_build" == false && "$skip_compose" == false &&
    "$skip_kubernetes" == false && "$matching_fleet_only" == false ]] ||
    simplematch_focused_fail \
      'focused CDC diagnostics require the full certification proof profile' || return 1

  SIMPLEMATCH_FOCUSED_KIND_CONTEXT="kind-$cluster"
  SIMPLEMATCH_FOCUSED_SOURCE_SIGNATURE="$source_signature"
  SIMPLEMATCH_FOCUSED_RETAINED_CDC_RUNTIME_SIGNATURE="$cdc_runtime_signature"
  SIMPLEMATCH_FOCUSED_RETAINED_CDC_VERIFIER_SIGNATURE="$cdc_verifier_signature"
  focused_image_transport="$image_transport"
}

simplematch_focused_remaining_seconds() {
  local remaining=$((focused_preflight_deadline_epoch - $(date +%s)))
  (( remaining > 0 )) || return 1
  printf '%s\n' "$remaining"
}

simplematch_focused_kubectl() {
  local remaining
  remaining="$(simplematch_focused_remaining_seconds)" || return 124
  timeout "$remaining" "$focused_kubectl_bin" "$@"
}

simplematch_focused_validate_namespace() {
  local namespace="$1"
  local namespace_json current_context namespace_run_id

  current_context="$(simplematch_focused_kubectl config current-context)" ||
    simplematch_focused_fail 'could not read the current Kubernetes context' || return 1
  [[ "$current_context" == "$SIMPLEMATCH_FOCUSED_KIND_CONTEXT" ]] ||
    simplematch_focused_fail \
      "current Kubernetes context=$current_context, expected $SIMPLEMATCH_FOCUSED_KIND_CONTEXT" ||
    return 1
  namespace_json="$(simplematch_focused_kubectl --context \
    "$SIMPLEMATCH_FOCUSED_KIND_CONTEXT" get namespace "$namespace" -o json)" ||
    simplematch_focused_fail "retained namespace does not exist: $namespace" || return 1
  jq -e --arg expected local-production-like-certification '
    .metadata.labels["simplematch.io/lifecycle"] == "disposable" and
    .metadata.labels["simplematch.io/managed-by"] == $expected and
    (.metadata.labels["simplematch.io/run-id"] | type == "string" and length > 0)
  ' <<<"$namespace_json" >/dev/null || simplematch_focused_fail \
    "retained namespace is not an owned disposable certification namespace: $namespace" ||
    return 1
  namespace_run_id="$(jq -er \
    '.metadata.labels["simplematch.io/run-id"]' <<<"$namespace_json")" ||
    simplematch_focused_fail 'retained namespace run-id is missing' || return 1
  [[ "$namespace_run_id" == "$(simplematch_focused_context_value run_id)" ]] ||
    simplematch_focused_fail \
      "retained namespace belongs to run $namespace_run_id, not $(simplematch_focused_context_value run_id)" ||
    return 1
}

simplematch_focused_collect_dependencies() {
  local phase_id="$1"
  local dependency dependency_output

  [[ -z "${SIMPLEMATCH_FOCUSED_DEPENDENCY_SEEN[$phase_id]+x}" ]] || return 0
  SIMPLEMATCH_FOCUSED_DEPENDENCY_SEEN["$phase_id"]=true
  dependency_output="$(certification_phase_dependencies "$phase_id")" || return 1
  while IFS= read -r dependency; do
    [[ -n "$dependency" ]] || continue
    simplematch_focused_collect_dependencies "$dependency" || return 1
  done <<<"$dependency_output"
  [[ "$phase_id" == kubernetes-cdc-delivery ]] ||
    SIMPLEMATCH_FOCUSED_DEPENDENCIES+=("$phase_id")
}

simplematch_focused_validate_dependencies() {
  local dependency result_path

  SIMPLEMATCH_FOCUSED_DEPENDENCIES=()
  declare -gA SIMPLEMATCH_FOCUSED_DEPENDENCY_SEEN=()
  simplematch_focused_collect_dependencies kubernetes-cdc-delivery ||
    simplematch_focused_fail \
      'could not resolve the kubernetes-cdc-delivery dependency graph' || return 1
  [[ -f "$focused_evidence_dir/plan.json" ]] || simplematch_focused_fail \
    "retained certification plan is missing: $focused_evidence_dir/plan.json" || return 1
  jq -e '.schemaVersion == 1 and (.phases | type == "array")' \
    "$focused_evidence_dir/plan.json" >/dev/null || simplematch_focused_fail \
    'retained certification plan is malformed' || return 1

  for dependency in "${SIMPLEMATCH_FOCUSED_DEPENDENCIES[@]}"; do
    result_path="$focused_evidence_dir/phases/$dependency/result.json"
    [[ -f "$result_path" ]] || simplematch_focused_fail \
      "dependency evidence is missing for $dependency" || return 1
    jq -e --arg phase "$dependency" '
      .schemaVersion == 1 and .phaseId == $phase and .status == "PASS"
    ' "$result_path" >/dev/null || simplematch_focused_fail \
      "dependency $dependency does not have a valid PASS result" || return 1
    jq -e --arg phase "$dependency" '
      any(.phases[]; .phaseId == $phase and .decision != "SKIP")
    ' "$focused_evidence_dir/plan.json" >/dev/null || simplematch_focused_fail \
      "dependency $dependency is not part of the retained executed plan" || return 1
  done
}

simplematch_focused_secret_value() {
  local secrets_json="$1"
  local secret_name="$2"
  local key="$3"
  local encoded

  encoded="$(jq -er --arg name "$secret_name" --arg key "$key" '
    .items[] | select(.metadata.name == $name) | .data[$key] // empty
  ' <<<"$secrets_json")" || return 1
  [[ -n "$encoded" ]] || return 1
  printf '%s' "$encoded" | base64 --decode
}

simplematch_focused_validate_kubernetes_inputs() {
  local namespace="$1"
  local matching_digest session_json artifact_json fix_spec_json risk_config_json
  local secrets_json password expected_dsn service application_yaml maximum_age

  matching_digest="$(simplematch_local_image_lock_digest \
    "$focused_image_lock" matching)" || simplematch_focused_fail \
    'could not resolve the retained Matching image digest' || return 1
  session_json="$(simplematch_focused_kubectl --context \
    "$SIMPLEMATCH_FOCUSED_KIND_CONTEXT" -n "$namespace" get configmap \
    matching-session-config -o json)" || simplematch_focused_fail \
    'could not read retained Matching session configuration' || return 1
  jq -e --arg tradingDay "$(simplematch_focused_context_value trading_day)" \
    --arg matchingDigest "$matching_digest" '
    .data.trading_day == $tradingDay and
    .data.trading_session_id == ($tradingDay + "-regular") and
    .data.matching_image_digest == $matchingDigest
  ' <<<"$session_json" >/dev/null || simplematch_focused_fail \
    'retained Matching session configuration does not match run identity or image lock' ||
    return 1

  artifact_json="$(simplematch_focused_kubectl --context \
    "$SIMPLEMATCH_FOCUSED_KIND_CONTEXT" -n "$namespace" get configmap \
    matching-daily-artifact -o json)" || simplematch_focused_fail \
    'could not read retained Matching artifact configuration' || return 1
  jq -e '.immutable == true' <<<"$artifact_json" >/dev/null || simplematch_focused_fail \
    'retained Matching artifact ConfigMap is not immutable' || return 1
  fix_spec_json="$(simplematch_focused_kubectl --context \
    "$SIMPLEMATCH_FOCUSED_KIND_CONTEXT" -n "$namespace" get configmap \
    quickfix-gateway-fix-spec -o json)" || simplematch_focused_fail \
    'could not read retained QuickFIX FIX44 dictionary configuration' || return 1
  jq -e '.immutable == true' <<<"$fix_spec_json" >/dev/null || simplematch_focused_fail \
    'retained QuickFIX FIX44 dictionary ConfigMap is not immutable' || return 1

  risk_config_json="$(simplematch_focused_kubectl --context \
    "$SIMPLEMATCH_FOCUSED_KIND_CONTEXT" -n "$namespace" get configmap \
    risk-service-config -o json)" || simplematch_focused_fail \
    'could not read retained Risk configuration' || return 1
  application_yaml="$(jq -er '.data["application.yaml"]' <<<"$risk_config_json")" ||
    simplematch_focused_fail 'retained Risk configuration has no application.yaml' || return 1
  maximum_age="$(sed -nE 's/.*maximum-metric-age:[[:space:]]*([0-9]+)s.*/\1/p' \
    <<<"$application_yaml" | head -1)"
  [[ "$maximum_age" =~ ^[1-9][0-9]*$ && "$maximum_age" -le 600 ]] ||
    simplematch_focused_fail \
      'retained Risk maximum-metric-age is missing or outside the bounded observer range' ||
    return 1

  password="${SIMPLEMATCH_LOCAL_POSTGRES_PASSWORD:-simplematch}"
  [[ "$password" =~ ^[A-Za-z0-9._~-]+$ ]] || simplematch_focused_fail \
    'SIMPLEMATCH_LOCAL_POSTGRES_PASSWORD contains unsupported local-lab characters' || return 1
  expected_dsn="postgresql://simplematch:${password}@postgres:5432/simplematch"
  secrets_json="$(simplematch_focused_kubectl --context \
    "$SIMPLEMATCH_FOCUSED_KIND_CONTEXT" -n "$namespace" get secrets -o json)" ||
    simplematch_focused_fail 'could not read retained PostgreSQL secrets' || return 1

  [[ "$(simplematch_focused_secret_value "$secrets_json" \
    simplematch-flyway-secrets postgres_dsn)" == "$expected_dsn" ]] ||
    simplematch_focused_fail 'retained Flyway PostgreSQL DSN does not match local configuration' ||
    return 1
  [[ "$(simplematch_focused_secret_value "$secrets_json" \
    simplematch-postgres-secrets postgres_user)" == simplematch ]] ||
    simplematch_focused_fail 'retained PostgreSQL user does not match local configuration' || return 1
  [[ "$(simplematch_focused_secret_value "$secrets_json" \
    simplematch-postgres-secrets postgres_password)" == "$password" ]] ||
    simplematch_focused_fail 'retained PostgreSQL password does not match local configuration' ||
    return 1
  for service in \
      account-service risk-service persistence market-data-projection \
      query-service quickfix-gateway; do
    [[ "$(simplematch_focused_secret_value "$secrets_json" \
      "${service}-secrets" postgres_dsn)" == "$expected_dsn" ]] ||
      simplematch_focused_fail \
        "retained PostgreSQL DSN does not match for $service" || return 1
  done
}

simplematch_focused_validate_workload_image_binding() {
  local workload_json="$1"
  local service="$2"
  local expected_image="$3"

  case "$service" in
    flyway-runner)
      jq -e --arg image "$expected_image" '
        [.items[]?
          | select((.metadata.name // "") | endswith("-flyway"))
          | .spec.template.spec.containers[]?.image] as $images
        | ($images | length > 0) and all($images[]; . == $image)
      ' <<<"$workload_json" >/dev/null || simplematch_focused_fail \
        'retained Flyway workloads are not all bound to the flyway image' || return 1
      ;;
    *)
      jq -e --arg name "$service" --arg image "$expected_image" '
        any(.items[]?;
          .metadata.name == $name and
          ([.spec.template.spec.containers[]?.image] as $images
            | ($images | length > 0) and all($images[]; . == $image))
        )
      ' <<<"$workload_json" >/dev/null || simplematch_focused_fail \
        "retained workload image binding does not match the immutable lock for $service" ||
        return 1
      ;;
  esac
}

simplematch_focused_validate_image_inputs() {
  local namespace="$1"
  local workload_json workload_images expected_image service entry
  local -a overlay_services=()

  [[ "$focused_image_transport" == registry ]] || simplematch_focused_fail \
    'focused CDC diagnostics require registry transport with immutable image references' || return 1
  [[ -f "$focused_image_lock" ]] || simplematch_focused_fail \
    "retained immutable image lock is missing: $focused_image_lock" || return 1
  simplematch_local_image_lock_validate_file "$focused_image_lock" ||
    simplematch_focused_fail 'retained immutable image lock failed validation' || return 1
  SIMPLEMATCH_FOCUSED_IMAGE_LOCK_DIGEST="$(sha256sum "$focused_image_lock" | awk '{print $1}')" ||
    simplematch_focused_fail 'could not fingerprint the retained image lock' || return 1
  [[ "$SIMPLEMATCH_FOCUSED_IMAGE_LOCK_DIGEST" =~ ^[0-9a-f]{64}$ ]] ||
    simplematch_focused_fail 'retained image lock fingerprint is not canonical' || return 1

  workload_json="$(simplematch_focused_kubectl --context \
    "$SIMPLEMATCH_FOCUSED_KIND_CONTEXT" -n "$namespace" \
    get deployments,statefulsets,jobs -o json)" || simplematch_focused_fail \
    'could not read retained workload image inputs' || return 1
  workload_images="$(jq -r '.items[]?.spec.template.spec.containers[]?.image // empty' \
    <<<"$workload_json")" || simplematch_focused_fail \
    'retained workload image document is malformed' || return 1
  [[ -n "$workload_images" ]] || simplematch_focused_fail \
    'retained workload image document contains no containers' || return 1
  mapfile -t overlay_services < <(simplematch_local_image_inventory_local_overlay_services)
  ((${#overlay_services[@]} > 0)) || simplematch_focused_fail \
    'local image inventory has no deployable services' || return 1
  for service in "${overlay_services[@]}"; do
    entry="$(simplematch_local_image_lock_entry "$focused_image_lock" "$service")" ||
      simplematch_focused_fail "retained image lock has no entry for $service" || return 1
    expected_image="${entry##*|}"
    simplematch_focused_validate_workload_image_binding \
      "$workload_json" "$service" "$expected_image" || return 1
  done
  grep -Fxq 'quay.io/debezium/connect:3.6.0.Final' <<<"$workload_images" ||
    simplematch_focused_fail \
      'retained Kafka Connect workload does not use the pinned Debezium 3.6 image' || return 1
}

simplematch_focused_validate_scoped_provenance() {
  local current_runtime_signature current_verifier_signature

  current_runtime_signature="$(
    simplematch_certification_cdc_runtime_signature "$focused_repo_root"
  )" || simplematch_focused_fail \
    'could not calculate the current CDC runtime signature' || return 1
  [[ "$current_runtime_signature" =~ ^[0-9a-f]{64}$ ]] || \
    simplematch_focused_fail \
      'current CDC runtime signature is not a canonical SHA-256' || return 1
  SIMPLEMATCH_FOCUSED_CURRENT_CDC_RUNTIME_SIGNATURE="$current_runtime_signature"
  [[ "$current_runtime_signature" == \
    "$SIMPLEMATCH_FOCUSED_RETAINED_CDC_RUNTIME_SIGNATURE" ]] || \
    simplematch_focused_fail \
      'retained CDC runtime signature differs; create a fresh full run' || return 1

  current_verifier_signature="$(
    simplematch_certification_cdc_verifier_signature "$focused_repo_root"
  )" || simplematch_focused_fail \
    'could not calculate the current CDC verifier signature' || return 1
  [[ "$current_verifier_signature" =~ ^[0-9a-f]{64}$ ]] || \
    simplematch_focused_fail \
      'current CDC verifier signature is not a canonical SHA-256' || return 1
  SIMPLEMATCH_FOCUSED_CURRENT_CDC_VERIFIER_SIGNATURE="$current_verifier_signature"
  if [[ "$current_verifier_signature" != \
    "$SIMPLEMATCH_FOCUSED_RETAINED_CDC_VERIFIER_SIGNATURE" ]]; then
    SIMPLEMATCH_FOCUSED_VERIFIER_CHANGED=true
  fi
}

simplematch_focused_validate_verifier_contract() {
  local remaining

  [[ -x "$focused_verifier_contract_script" ]] || simplematch_focused_fail \
    "CDC verifier contract script is missing or not executable: $focused_verifier_contract_script" ||
    return 1
  [[ -n "$focused_verifier_contract_output" ]] || simplematch_focused_fail \
    'CDC verifier contract output path is not configured' || return 1
  remaining="$(simplematch_focused_remaining_seconds)" || simplematch_focused_fail \
    'focused preflight deadline expired before the CDC verifier contract' || return 1
  if ! timeout "$remaining" "$focused_verifier_contract_script" \
      >"$focused_verifier_contract_output" 2>&1; then
    simplematch_focused_fail \
      "CDC verifier contract failed; inspect $focused_verifier_contract_output" || return 1
  fi
}

simplematch_focused_preflight() {
  local namespace

  simplematch_focused_load_context "$focused_evidence_dir/run-context" || return 1
  simplematch_focused_validate_context || return 1
  SIMPLEMATCH_FOCUSED_CURRENT_REVISION="$(
    simplematch_certification_source_revision "$focused_repo_root"
  )" || simplematch_focused_fail 'current certification source is not clean' || return 1
  [[ -n "$SIMPLEMATCH_FOCUSED_CURRENT_REVISION" ]] || simplematch_focused_fail \
    'current certification source revision is empty' || return 1
  simplematch_focused_validate_scoped_provenance || return 1

  namespace="$(simplematch_focused_context_value namespace)"
  simplematch_focused_validate_namespace "$namespace" || return 1
  simplematch_focused_validate_dependencies || return 1
  simplematch_focused_validate_image_inputs "$namespace" || return 1
  simplematch_focused_validate_kubernetes_inputs "$namespace" || return 1
  simplematch_focused_validate_verifier_contract || return 1
}
