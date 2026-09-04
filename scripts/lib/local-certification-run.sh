#!/usr/bin/env bash

# Sourced by run-local-production-like-certification.sh. This file maps phase
# identifiers to execution adapters. PhaseGraph and CertificationPlanner own
# which phases run and in what order.

rendered_manifest="$evidence_dir/local-kubernetes.yaml"
platform_manifest="$evidence_dir/local-kubernetes-platform.yaml"
migration_manifest="$evidence_dir/local-kubernetes-migrations.yaml"
workload_manifest="$evidence_dir/local-kubernetes-workloads.yaml"
input_manifest="$evidence_dir/local-kubernetes-inputs.yaml"
matching_workload_manifest="$evidence_dir/local-kubernetes-matching-workload.yaml"
registry_fragment_directory="$evidence_dir/image-lock-fragments"

_certification_kafka_topic_for_phase() {
  case "$1" in
    kafka-create-matching-commands) printf '%s\n' matching.commands ;;
    kafka-create-matching-events) printf '%s\n' matching.events ;;
    kafka-create-account-lifecycle) printf '%s\n' account.lifecycle ;;
    kafka-create-marketdata-events) printf '%s\n' marketdata.events ;;
    *) return 1 ;;
  esac
}

_certification_render_and_split_kubernetes_manifest() {
  render_local_kubernetes_manifest >/dev/null || return 1
  split_kubernetes_manifest \
    "$rendered_manifest" "$platform_manifest" "$migration_manifest" \
    "$workload_manifest" "$input_manifest"
}

_certification_matching_digest() {
  simplematch_local_image_transport_matching_digest \
    "$image_transport" "$image_tag" "$image_lock"
}

_certification_matching_reference() {
  simplematch_local_image_transport_matching_reference \
    "$image_transport" "$image_tag" "$image_lock"
}

_certification_matching_digest_argument() {
  if [[ "$dry_run" == true ]]; then
    printf '%s\n' 'sha256:0000000000000000000000000000000000000000000000000000000000000000'
    return 0
  fi
  _certification_matching_digest
}

_certification_matching_reference_argument() {
  if [[ "$dry_run" == true ]]; then
    printf '%s\n' 'simplematch-matching:dry-run'
    return 0
  fi
  _certification_matching_reference
}

_certification_namespace_state_valid() {
  local namespace_run_id

  kubectl --context "$kind_context" get namespace "$namespace" >/dev/null 2>&1 || \
    return 1
  simplematch_kind_namespace_is_disposable \
    "$kind_context" "$namespace" local-production-like-certification || return 1
  namespace_run_id="$(kubectl --context "$kind_context" get namespace "$namespace" \
    -o jsonpath='{.metadata.labels.simplematch\.io/run-id}')" || return 1
  [[ "$namespace_run_id" == "$run_id" ]]
}

_certification_secret_value() {
  local secret_name="$1"
  local key="$2"
  local encoded

  encoded="$(kubectl --context "$kind_context" -n "$namespace" \
    get secret "$secret_name" -o "jsonpath={.data.${key}}")" || return 1
  [[ -n "$encoded" ]] || return 1
  printf '%s' "$encoded" | base64 --decode
}

_certification_kubernetes_inputs_valid() {
  local matching_digest session_values service

  _certification_namespace_state_valid || return 1
  matching_digest="$(_certification_matching_digest)" || return 1
  [[ "$(kubectl --context "$kind_context" -n "$namespace" \
      get configmap matching-daily-artifact -o jsonpath='{.immutable}')" == true ]] || return 1
  [[ "$(kubectl --context "$kind_context" -n "$namespace" \
      get configmap quickfix-gateway-fix-spec -o jsonpath='{.immutable}')" == true ]] || return 1
  session_values="$(kubectl --context "$kind_context" -n "$namespace" \
    get configmap matching-session-config \
    -o jsonpath='{.data.trading_day}|{.data.trading_session_id}|{.data.matching_image_digest}')" || \
    return 1
  [[ "$session_values" == \
    "$certification_trading_day|${certification_trading_day}-regular|$matching_digest" ]] || return 1

  [[ "$(_certification_secret_value simplematch-flyway-secrets postgres_dsn)" == \
    "$local_postgres_dsn" ]] || return 1
  [[ "$(_certification_secret_value simplematch-postgres-secrets postgres_user)" == \
    simplematch ]] || return 1
  [[ "$(_certification_secret_value simplematch-postgres-secrets postgres_password)" == \
    "$local_postgres_password" ]] || return 1
  for service in \
      account-service risk-service persistence market-data-projection \
      query-service quickfix-gateway; do
    [[ "$(_certification_secret_value "${service}-secrets" postgres_dsn)" == \
      "$local_postgres_dsn" ]] || return 1
  done
}

certification_phase_resume_validate() {
  case "$1" in
    kubernetes-namespace)
      _certification_namespace_state_valid
      ;;
    *)
      return 1
      ;;
  esac
}

_certification_create_or_validate_namespace() {
  if [[ "$resume" == true ]]; then
    _certification_namespace_state_valid || {
      printf 'retained certification namespace no longer matches run ownership: %s\n' \
        "$namespace" >&2
      return 1
    }
    printf 'Validated retained certification namespace %s.\n' "$namespace"
    return 0
  fi
  create_certification_namespace
}

_certification_apply_or_validate_kubernetes_inputs() {
  local matching_digest="$1"
  local manifest="$2"

  if [[ "$resume" == true ]]; then
    _certification_kubernetes_inputs_valid || {
      printf '%s\n' \
        'retained Kubernetes inputs do not match the current run identity and configuration' >&2
      return 1
    }
    printf '%s\n' 'Validated retained Kubernetes inputs.'
    return 0
  fi
  apply_local_kubernetes_inputs "$matching_digest" "$manifest"
}

_certification_run_kafka_topic_create() {
  local phase_id="$1"
  local topic

  topic="$(_certification_kafka_topic_for_phase "$phase_id")" || return 1
  run_logged "$phase_id" "${compose_command[@]}" exec -T kafka-1 \
    /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka-1:29092 \
    --create --if-not-exists --topic "$topic" --partitions 15 \
    --replication-factor 3 --config cleanup.policy=delete \
    --config retention.ms=2592000000 --config min.insync.replicas=2
}

_certification_run_kafka_capture() {
  local phase_id="$1"
  local fixture_dir="$evidence_dir/kafka-fixture"

  mkdir -p "$fixture_dir"
  case "$phase_id" in
    kafka-describe-matching-commands)
      run_capture "$phase_id" "$fixture_dir/matching.commands.topic.txt" \
        "${compose_command[@]}" exec -T kafka-1 /opt/kafka/bin/kafka-topics.sh \
        --bootstrap-server kafka-1:29092 --describe --topic matching.commands
      ;;
    kafka-config-matching-commands)
      run_capture "$phase_id" "$fixture_dir/matching.commands.config.txt" \
        "${compose_command[@]}" exec -T kafka-1 /opt/kafka/bin/kafka-configs.sh \
        --bootstrap-server kafka-1:29092 --entity-type topics \
        --entity-name matching.commands --describe
      ;;
    kafka-describe-matching-events)
      run_capture "$phase_id" "$fixture_dir/matching.events.topic.txt" \
        "${compose_command[@]}" exec -T kafka-1 /opt/kafka/bin/kafka-topics.sh \
        --bootstrap-server kafka-1:29092 --describe --topic matching.events
      ;;
    kafka-config-matching-events)
      run_capture "$phase_id" "$fixture_dir/matching.events.config.txt" \
        "${compose_command[@]}" exec -T kafka-1 /opt/kafka/bin/kafka-configs.sh \
        --bootstrap-server kafka-1:29092 --entity-type topics \
        --entity-name matching.events --describe
      ;;
    kafka-broker-config)
      run_capture "$phase_id" "$fixture_dir/broker.config.txt" \
        "${compose_command[@]}" exec -T kafka-1 \
        cat /opt/kafka/config/server.properties
      ;;
    *)
      return 1
      ;;
  esac
}

_certification_run_registry_publish() {
  local phase_id="$1"
  local service="${phase_id#registry-publish/}"
  local fragment_file="$registry_fragment_directory/${service}.lock"

  [[ "$dry_run" == true ]] || mkdir -p "$registry_fragment_directory" || return 1
  run_logged "$phase_id" bash "$repo_root/scripts/publish-local-images.sh" \
    --tag "$image_tag" --service "$service" --output "$fragment_file"
}

_certification_run_kind_load() {
  local -a args=(
    --transport kind-load
    --tag "$image_tag"
    --cluster "$kind_cluster"
    --image-lock "$image_lock"
  )
  [[ "$matching_fleet_only" == true ]] && args+=(--matching-only)
  run_logged kind-load-import bash \
    "$repo_root/scripts/prepare-local-kubernetes-images.sh" "${args[@]}"
}

certification_execute_phase() {
  local phase_id="$1"
  local service matching_digest matching_reference

  case "$phase_id" in
    source-preflight)
      run_logged "$phase_id" simplematch_certification_source_revision "$repo_root"
      ;;
    static-kubernetes-overlays)
      run_logged "$phase_id" bash "$repo_root/scripts/test-kubernetes-overlays.sh"
      ;;
    static-phase1-deployment-contracts)
      run_logged "$phase_id" bash \
        "$repo_root/scripts/test-phase1-deployment-contracts.sh"
      ;;
    static-kubernetes-dependencies)
      run_logged "$phase_id" bash \
        "$repo_root/scripts/test-local-kubernetes-dependencies.sh"
      ;;
    static-matching-manifests)
      run_logged "$phase_id" bash \
        "$repo_root/scripts/test-matching-kubernetes-manifests.sh"
      ;;
    static-matching-profile)
      run_logged "$phase_id" bash "$repo_root/scripts/test-matching-topic-profile.sh"
      ;;
    static-flyway-services)
      run_logged "$phase_id" bash "$repo_root/scripts/test-flyway-services.sh"
      ;;
    compose-config)
      run_logged "$phase_id" "${compose_command[@]}" config
      ;;
    cdc-outbox-failure-live)
      run_logged "$phase_id" bash \
        "$repo_root/scripts/run-outbox-cdc-contract-check.sh"
      ;;
    local-image-inventory)
      run_logged "$phase_id" bash "$repo_root/scripts/build-local-images.sh" --list
      ;;
    local-image-build/*)
      service="${phase_id#local-image-build/}"
      run_logged "$phase_id" bash "$repo_root/scripts/build-local-images.sh" \
        --tag "$image_tag" --service "$service"
      ;;
    kafka-producer-contract)
      run_logged "$phase_id" bash \
        "$repo_root/scripts/validate-matching-producer-contract.sh" \
        --output "$matching_producer_config_file"
      ;;
    compose-up)
      compose_started=true
      run_logged "$phase_id" "${compose_command[@]}" \
        up --detach --remove-orphans
      ;;
    compose-wait)
      run_logged "$phase_id" wait_for_compose
      ;;
    compose-status)
      run_logged "$phase_id" "${compose_command[@]}" ps
      ;;
    kafka-capacity-evidence)
      run_logged "$phase_id" generate_kafka_capacity_evidence
      ;;
    kafka-create-*)
      _certification_run_kafka_topic_create "$phase_id"
      ;;
    kafka-describe-*|kafka-config-*|kafka-broker-config)
      _certification_run_kafka_capture "$phase_id"
      ;;
    kafka-profile-validation)
      run_logged "$phase_id" bash "$repo_root/scripts/validate-matching-topic-profile.sh" \
        --profile production --fixture-dir "$evidence_dir/kafka-fixture" \
        --producer-config-file "$matching_producer_config_file" \
        --capacity-evidence-file "$matching_capacity_evidence_file" \
        --certify-production
      ;;
    kafka-broker-failure-live)
      run_logged "$phase_id" bash \
        "$repo_root/scripts/run-matching-kafka-failure-check.sh" \
        --compose-project "$compose_project" --compose-file "$compose_file" \
        --evidence-dir "$evidence_dir/kafka-failure" \
        --producer-config-file "$matching_producer_config_file" \
        --capacity-evidence-file "$matching_capacity_evidence_file"
      ;;
    compose-down-before-kubernetes)
      run_logged "$phase_id" "${compose_command[@]}" \
        down --volumes --remove-orphans || return 1
      [[ "$dry_run" == true ]] || compose_started=false
      ;;
    registry-connectivity)
      run_logged "$phase_id" simplematch_registry_verify "$kind_cluster"
      ;;
    registry-publish/*)
      _certification_run_registry_publish "$phase_id"
      ;;
    registry-image-lock)
      run_logged "$phase_id" certification_construct_registry_image_lock
      ;;
    kind-load-import)
      _certification_run_kind_load
      ;;
    kubernetes-manifest-split)
      run_logged "$phase_id" _certification_render_and_split_kubernetes_manifest
      ;;
    kubernetes-namespace)
      run_logged "$phase_id" _certification_create_or_validate_namespace
      ;;
    kubernetes-inputs)
      matching_digest="$(_certification_matching_digest_argument)" || return 1
      run_logged "$phase_id" _certification_apply_or_validate_kubernetes_inputs \
        "$matching_digest" "$input_manifest"
      ;;
    kubernetes-platform-apply)
      run_logged "$phase_id" kubectl apply -f "$platform_manifest"
      ;;
    kubernetes-migrations)
      run_logged "$phase_id" apply_kubernetes_migrations "$migration_manifest"
      ;;
    kubernetes-matching-manifest)
      run_logged "$phase_id" select_matching_workload \
        "$workload_manifest" "$matching_workload_manifest"
      ;;
    kubernetes-topic-provisioning)
      run_logged "$phase_id" apply_kubernetes_topic_provisioning "$migration_manifest"
      ;;
    kubernetes-open-barriers)
      matching_digest="$(_certification_matching_digest_argument)" || return 1
      matching_reference="$(_certification_matching_reference_argument)" || return 1
      run_logged "$phase_id" publish_local_matching_open_barriers \
        "$matching_digest" "$matching_reference"
      ;;
    kubernetes-workload-apply)
      run_logged "$phase_id" kubectl apply -f "$workload_manifest"
      ;;
    kubernetes-matching-apply)
      run_logged "$phase_id" kubectl apply -f "$matching_workload_manifest"
      ;;
    kubernetes-risk-outbox-connector)
      run_logged "$phase_id" register_kubernetes_risk_connector
      ;;
    kubernetes-account-outbox-connector)
      run_logged "$phase_id" register_kubernetes_account_connector
      ;;
    kubernetes-workloads)
      run_logged "$phase_id" wait_for_kubernetes_workloads
      ;;
    kubernetes-cdc-delivery)
      run_logged "$phase_id" bash \
        "$repo_root/scripts/run-risk-cdc-delivery-observer-check.sh" \
        --namespace "$namespace" --namespace-run-id "$run_id" \
        --evidence-dir "$evidence_dir/cdc-delivery"
      ;;
    kubernetes-matching-workloads)
      run_logged "$phase_id" wait_for_local_matching_fleet
      ;;
    kubernetes-fleet)
      run_logged "$phase_id" verify_local_matching_fleet
      ;;
    retained-run-provenance)
      run_logged "$phase_id" simplematch_record_certification_provenance \
        "$repo_root" "$evidence_dir" "$namespace" \
        "$image_transport" "$image_tag" "$image_lock"
      ;;
    *)
      printf 'no certification execution adapter exists for phase %s\n' \
        "$phase_id" >&2
      return 1
      ;;
  esac
}

certification_plan_execute certification_execute_phase || die \
  'Certification phase execution failed.'

printf '%s\n' 'Local production-like certification workflow completed.'
