#!/usr/bin/env bash

# Sourced by run-local-production-like-certification.sh. This file owns the
# final certification phase ordering and has no independent entry point.

if [[ "$skip_compose" == false ]]; then
  compose_started=true
  run_logged compose-up "${compose_command[@]}" up --detach --remove-orphans
  run_logged compose-wait wait_for_compose
  run_logged compose-status "${compose_command[@]}" ps
  run_logged kafka-capacity-evidence generate_kafka_capacity_evidence
  if [[ -z "${SIMPLEMATCH_KAFKA_PRODUCER_CONFIG_FILE:-}" ]]; then
    run_logged kafka-producer-contract bash \
      "$repo_root/scripts/validate-matching-producer-contract.sh" \
      --output "$matching_producer_config_file"
  fi
  create_kafka_topics
  collect_kafka_fixture
  run_logged kafka-broker-failure-live bash \
    "$repo_root/scripts/run-matching-kafka-failure-check.sh" \
    --compose-project "$compose_project" --compose-file "$compose_file" \
    --evidence-dir "$evidence_dir/kafka-failure" \
    --producer-config-file "$matching_producer_config_file" \
    --capacity-evidence-file "$matching_capacity_evidence_file"
  if [[ "$matching_fleet_only" == false ]]; then
    printf '%s\n' 'Compose Flyway phase omitted; Kubernetes Flyway Jobs own the local schema.'
  else
    printf '%s\n' 'Compose Flyway phases skipped for the Matching fleet-only gate.'
  fi
else
  printf '%s\n' 'Compose runtime phases skipped.'
fi

if [[ "$skip_kubernetes" == false ]]; then
  prepare_image_args=(
    --tag "$image_tag"
    --cluster "$kind_cluster"
    --image-lock "$image_lock"
  )
  [[ "$matching_fleet_only" == true ]] && prepare_image_args+=(--matching-only)

  if [[ "$dry_run" == true ]]; then
    print_command bash "$repo_root/scripts/prepare-local-kubernetes-images.sh" "${prepare_image_args[@]}" --dry-run
    render_args=(
      --image-lock "$image_lock"
      --namespace "$namespace"
      --output "$evidence_dir/local-kubernetes.yaml"
    )
    print_command bash "$repo_root/scripts/render-local-kubernetes-manifest.sh" "${render_args[@]}"
    print_command kubectl create namespace "$namespace"
    print_command kubectl label namespace "$namespace" \
      simplematch.io/lifecycle=disposable \
      simplematch.io/managed-by=local-production-like-certification \
      "simplematch.io/run-id=${run_id}"
    print_command kubectl create -f "$evidence_dir/local-kubernetes-inputs.yaml"
    print_command kubectl apply -f "$evidence_dir/local-kubernetes-platform.yaml"
    print_command kubectl apply -f "$evidence_dir/local-kubernetes-migrations.yaml"
    print_command kubectl wait --for=condition=complete job/account-service-flyway --timeout=300s
    print_command kubectl apply -f "$evidence_dir/local-kubernetes-workloads.yaml"
    print_command register_kubernetes_risk_connector
    print_command bash "$repo_root/scripts/verify-matching-fleet-live.sh" \
      --namespace "$namespace" --allow-shared-node
  else
    run_refreshable_logged kubernetes-image-transport \
      bash "$repo_root/scripts/prepare-local-kubernetes-images.sh" "${prepare_image_args[@]}"

    matching_digest="$(simplematch_local_image_lock_digest "$image_lock" matching)" || die \
      'Unable to resolve Matching image digest from the local image lock.'
    matching_image_reference="$(simplematch_local_image_lock_digest_reference "$image_lock" matching)" || die \
      'Unable to resolve Matching image reference from the local image lock.'

    rendered_manifest="$(render_local_kubernetes_manifest)"
    platform_manifest="$evidence_dir/local-kubernetes-platform.yaml"
    migration_manifest="$evidence_dir/local-kubernetes-migrations.yaml"
    workload_manifest="$evidence_dir/local-kubernetes-workloads.yaml"
    input_manifest="$evidence_dir/local-kubernetes-inputs.yaml"
    run_logged kubernetes-manifest-split split_kubernetes_manifest \
      "$rendered_manifest" "$platform_manifest" "$migration_manifest" "$workload_manifest" "$input_manifest"
    if [[ "$resume" == true ]]; then
      printf 'Reusing certification namespace %s.\n' "$namespace"
    else
      create_certification_namespace
    fi
    run_logged kubernetes-inputs apply_local_kubernetes_inputs "$matching_digest" "$input_manifest"
    run_logged kubernetes-platform-apply kubectl apply -f "$platform_manifest"
    if [[ "$matching_fleet_only" == true ]]; then
      matching_workload_manifest="$evidence_dir/local-kubernetes-matching-workload.yaml"
      run_logged kubernetes-matching-manifest select_matching_workload \
        "$workload_manifest" "$matching_workload_manifest"
      run_logged kubernetes-topic-provisioning apply_kubernetes_topic_provisioning \
        "$migration_manifest"
      run_logged kubernetes-open-barriers publish_local_matching_open_barriers \
        "$matching_digest" "$matching_image_reference"
      run_logged kubernetes-matching-apply kubectl apply -f "$matching_workload_manifest"
    else
      run_logged kubernetes-migrations apply_kubernetes_migrations "$migration_manifest"
      run_logged kubernetes-open-barriers publish_local_matching_open_barriers \
        "$matching_digest" "$matching_image_reference"
      run_logged kubernetes-workload-apply kubectl apply -f "$workload_manifest"
      run_logged kubernetes-risk-outbox-connector register_kubernetes_risk_connector
    fi
    if [[ "$matching_fleet_only" == true ]]; then
      run_logged kubernetes-matching-workloads wait_for_local_matching_fleet
    else
      run_logged kubernetes-workloads wait_for_kubernetes_workloads
    fi
    run_logged kubernetes-fleet verify_local_matching_fleet
  fi
else
  printf '%s\n' 'Kubernetes runtime phases skipped.'
fi

printf '%s\n' 'Local production-like certification workflow completed.'
