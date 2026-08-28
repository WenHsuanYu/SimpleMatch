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
    printf '%s\n' \
      'Compose Flyway phase omitted; Kubernetes Flyway Jobs own the local schema.'
  else
    printf '%s\n' \
      'Compose Flyway phases skipped for the Matching fleet-only gate.'
  fi
else
  printf '%s\n' 'Compose runtime phases skipped.'
fi

# Compose is a verification fixture, not part of the Kubernetes runtime. Running
# both at once doubles local Kafka/PostgreSQL pressure and can invalidate the
# production-like Kubernetes observation.
if [[ "$skip_compose" == false && "$skip_kubernetes" == false ]]; then
  run_logged compose-down-before-kubernetes \
    "${compose_command[@]}" down --volumes --remove-orphans
  [[ "$dry_run" == true ]] || compose_started=false
fi

if [[ "$skip_kubernetes" == false ]]; then
  if [[ "$image_transport" == registry ]]; then
    certification_publish_registry_images || die \
      'Incremental local registry image preparation failed.'
  else
    kind_load_args=(
      --transport kind-load
      --tag "$image_tag"
      --cluster "$kind_cluster"
      --image-lock "$image_lock"
    )
    [[ "$matching_fleet_only" == true ]] && kind_load_args+=(--matching-only)
    run_logged kind-load-import bash \
      "$repo_root/scripts/prepare-local-kubernetes-images.sh" \
      "${kind_load_args[@]}"
  fi

  if [[ "$dry_run" == true ]]; then
    render_args=(
      --transport "$image_transport"
      --image-lock "$image_lock"
      --namespace "$namespace"
      --output "$evidence_dir/local-kubernetes.yaml"
    )
    print_command bash \
      "$repo_root/scripts/render-local-kubernetes-manifest.sh" \
      "${render_args[@]}"
    print_command kubectl create namespace "$namespace"
    print_command kubectl label namespace "$namespace" \
      simplematch.io/lifecycle=disposable \
      simplematch.io/managed-by=local-production-like-certification \
      "simplematch.io/run-id=${run_id}"
    print_command kubectl create -f "$evidence_dir/local-kubernetes-inputs.yaml"
    print_command kubectl apply -f "$evidence_dir/local-kubernetes-platform.yaml"
    if [[ "$matching_fleet_only" == false ]]; then
      print_command kubectl apply -f \
        "$evidence_dir/local-kubernetes-migrations.yaml"
    fi
    print_command supervise_kubernetes_job kafka-topic-provisioning \
      "$kafka_topic_provisioning_supervisor_seconds" \
      "$evidence_dir/kubernetes-jobs/kafka-topic-provisioning"
    print_command kubectl apply -f \
      "$evidence_dir/local-kubernetes-workloads.yaml"
    if [[ "$matching_fleet_only" == false ]]; then
      print_command register_kubernetes_risk_connector
    fi
    if [[ "$image_transport" == kind-load ]]; then
      print_command bash "$repo_root/scripts/verify-matching-fleet-live.sh" \
        --namespace "$namespace" --allow-shared-node \
        --allow-local-image "simplematch-matching:${image_tag}"
    else
      print_command bash "$repo_root/scripts/verify-matching-fleet-live.sh" \
        --namespace "$namespace" --allow-shared-node
    fi
  else
    matching_digest="$(simplematch_local_image_transport_matching_digest \
      "$image_transport" "$image_tag" "$image_lock")" || die \
      "Unable to resolve Matching image digest for transport=$image_transport"
    matching_image_reference="$(simplematch_local_image_transport_matching_reference \
      "$image_transport" "$image_tag" "$image_lock")" || die \
      "Unable to resolve Matching image reference for transport=$image_transport"

    rendered_manifest="$(render_local_kubernetes_manifest)"
    platform_manifest="$evidence_dir/local-kubernetes-platform.yaml"
    migration_manifest="$evidence_dir/local-kubernetes-migrations.yaml"
    workload_manifest="$evidence_dir/local-kubernetes-workloads.yaml"
    input_manifest="$evidence_dir/local-kubernetes-inputs.yaml"
    run_logged kubernetes-manifest-split split_kubernetes_manifest \
      "$rendered_manifest" "$platform_manifest" "$migration_manifest" \
      "$workload_manifest" "$input_manifest"

    if [[ "$resume" == true ]]; then
      printf 'Reusing certification namespace %s.\n' "$namespace"
    else
      run_logged kubernetes-namespace create_certification_namespace
    fi
    run_logged kubernetes-inputs apply_local_kubernetes_inputs \
      "$matching_digest" "$input_manifest"
    run_logged kubernetes-platform-apply kubectl apply -f "$platform_manifest"

    if [[ "$matching_fleet_only" == true ]]; then
      matching_workload_manifest="$evidence_dir/local-kubernetes-matching-workload.yaml"
      run_logged kubernetes-matching-manifest select_matching_workload \
        "$workload_manifest" "$matching_workload_manifest"
      run_logged kubernetes-topic-provisioning apply_kubernetes_topic_provisioning \
        "$migration_manifest"
      run_logged kubernetes-open-barriers publish_local_matching_open_barriers \
        "$matching_digest" "$matching_image_reference"
      run_logged kubernetes-matching-apply kubectl apply \
        -f "$matching_workload_manifest"
      run_logged kubernetes-matching-workloads wait_for_local_matching_fleet
    else
      run_logged kubernetes-migrations apply_kubernetes_migrations \
        "$migration_manifest"
      run_logged kubernetes-open-barriers publish_local_matching_open_barriers \
        "$matching_digest" "$matching_image_reference"
      run_logged kubernetes-workload-apply kubectl apply -f "$workload_manifest"
      run_logged kubernetes-risk-outbox-connector register_kubernetes_risk_connector
      run_logged kubernetes-workloads wait_for_kubernetes_workloads
    fi
    run_logged kubernetes-fleet verify_local_matching_fleet
  fi
else
  printf '%s\n' 'Kubernetes runtime phases skipped.'
fi

printf '%s\n' 'Local production-like certification workflow completed.'
