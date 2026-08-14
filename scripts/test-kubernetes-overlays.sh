#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
temporary_directory="$(mktemp -d "${TMPDIR:-/tmp}/simplematch-kustomize.XXXXXX")"
trap 'rm -rf "$temporary_directory"' EXIT

for overlay in local test staging production; do
  rendered="$temporary_directory/$overlay.yaml"
  kubectl kustomize "$repo_root/deploy/k8s/overlays/$overlay" \
    --load-restrictor LoadRestrictionsNone >"$rendered"
  ruby - "$rendered" "$overlay" <<'RUBY'
require "yaml"

rendered_path, overlay = ARGV
visitor = Psych::Visitors::ToRuby.create
documents = Psych.parse_stream(File.read(rendered_path, encoding: "UTF-8")).children.map { |document| visitor.accept(document) }.compact
resources = documents.to_h { |document| [[document.fetch("kind"), document.fetch("metadata").fetch("name")], document] }

required_deployments = %w[
  account-service
  risk-service
  persistence
  market-data-projection
  marketdata-publisher
  marketdata-streamer
  query-service
]
required_deployments.each do |name|
  deployment = resources.fetch(["Deployment", name])
  pod = deployment.fetch("spec").fetch("template")
  container = pod.fetch("spec").fetch("containers").first
  abort "#{overlay}: #{name} has no service account" unless pod.fetch("spec").key?("serviceAccountName")
  abort "#{overlay}: #{name} has no readiness probe" unless container.key?("readinessProbe")
  abort "#{overlay}: #{name} has no startup probe" unless container.key?("startupProbe")
  abort "#{overlay}: #{name} has no liveness probe" unless container.key?("livenessProbe")
  security = container.fetch("securityContext")
  abort "#{overlay}: #{name} is not non-root" unless security.fetch("runAsNonRoot")
  abort "#{overlay}: #{name} is not read-only root" unless security.fetch("readOnlyRootFilesystem")

  env = container.fetch("env").to_h { |entry| [entry.fetch("name"), entry] }
  %w[
    SPRING_CLOUD_KUBERNETES_CONFIG_INCLUDE_PROFILE_SPECIFIC_SOURCES
    SPRING_CLOUD_KUBERNETES_SECRETS_INCLUDE_PROFILE_SPECIFIC_SOURCES
  ].each do |name|
    abort "#{overlay}: #{name} does not disable implicit profile-specific Kubernetes sources" unless
      env.dig(name, "value") == "false"
  end
end

%w[
  account-service
  risk-service
  persistence
  market-data-projection
  marketdata-publisher
  query-service
  quickfix-gateway
].each do |name|
  job = resources.fetch(["Job", "#{name}-flyway"])
  container = job.fetch("spec").fetch("template").fetch("spec").fetch("containers").first
  gradle_environment = container.fetch("env").to_h { |entry| [entry.fetch("name"), entry] }
  abort "#{overlay}: #{name} Flyway Job does not use the writable temporary Gradle user home" unless
    gradle_environment.fetch("GRADLE_USER_HOME") ==
      { "name" => "GRADLE_USER_HOME", "value" => "/tmp/gradle" }
  abort "#{overlay}: #{name} Flyway Job does not use the writable temporary Gradle project cache" unless
    gradle_environment.fetch("SIMPLEMATCH_GRADLE_PROJECT_CACHE_DIR") ==
      { "name" => "SIMPLEMATCH_GRADLE_PROJECT_CACHE_DIR", "value" => "/tmp/gradle-project" }
  build_mount = container.fetch("volumeMounts").find { |mount| mount["mountPath"] == "/workspace/build" }
  abort "#{overlay}: #{name} Flyway Job does not mount a writable build output directory" unless
    build_mount == { "name" => "build-output", "mountPath" => "/workspace/build" }
  abort "#{overlay}: #{name} Flyway Job does not define the build output volume" unless
    job.fetch("spec").fetch("template").fetch("spec").fetch("volumes").include?("name" => "build-output", "emptyDir" => {})
end

if overlay == "local"
  quickfix = resources.fetch(["StatefulSet", "quickfix-gateway"])
  quickfix_container = quickfix.fetch("spec").fetch("template").fetch("spec").fetch("containers").first
  quickfix_env = quickfix_container.fetch("env").to_h { |entry| [entry.fetch("name"), entry] }
  abort "local: QuickFIX Gateway must use the local Spring profile" unless
    quickfix_env.dig("SPRING_PROFILES_ACTIVE", "value") == "local"

  {
    "account-service" => ["SIMPLEMATCH_POSTGRES_DSN"],
    "risk-service" => ["SIMPLEMATCH_POSTGRES_DSN", "SIMPLEMATCH_TRADING_DAY", "SIMPLEMATCH_MATCHING_IMAGE_DIGEST"],
    "persistence" => ["SIMPLEMATCH_POSTGRES_DSN"],
    "market-data-projection" => ["SIMPLEMATCH_POSTGRES_DSN"],
    "marketdata-publisher" => ["SIMPLEMATCH_POSTGRES_DSN"],
    "query-service" => ["SIMPLEMATCH_POSTGRES_DSN", "SIMPLEMATCH_TRADING_DAY"]
  }.each do |deployment_name, environment_names|
    deployment = resources.fetch(["Deployment", deployment_name])
    env = deployment.fetch("spec").fetch("template").fetch("spec").fetch("containers").first.fetch("env")
    environment = env.to_h { |entry| [entry.fetch("name"), entry] }
    environment_names.each do |environment_name|
      entry = environment.fetch(environment_name)
      if environment_name == "SIMPLEMATCH_POSTGRES_DSN"
        reference = entry.fetch("valueFrom").fetch("secretKeyRef")
        expected = { "name" => "#{deployment_name}-secrets", "key" => "postgres_dsn" }
        abort "local: #{deployment_name} is missing its secret-backed PostgreSQL DSN" unless reference == expected
      else
        reference = entry.fetch("valueFrom").fetch("configMapKeyRef")
        expected_key = environment_name == "SIMPLEMATCH_TRADING_DAY" ? "trading_day" : "matching_image_digest"
        abort "local: #{deployment_name} is missing #{environment_name} from matching-session-config" unless
          reference == { "name" => "matching-session-config", "key" => expected_key }
      end
    end
  end

  matching = resources.fetch(["StatefulSet", "matching"])
  matching_container = matching.fetch("spec").fetch("template").fetch("spec").fetch("containers").first
  matching_env = matching_container.fetch("env").filter_map do |entry|
    entry.key?("value") ? [entry.fetch("name"), entry.fetch("value")] : nil
  end.to_h
  abort "local: Matching native workload does not bound local preallocation" unless
    matching_env.fetch("MATCHING_MAX_RESTING_ORDERS_PER_INSTRUMENT") == "1024" &&
      matching_env.fetch("MATCHING_OUTPUT_CAPACITY") == "262144" &&
      matching_env.fetch("MATCHING_MAX_PENDING_PUBLICATIONS") == "250000"
  abort "local: Matching native workload is under-provisioned for startup" unless
    matching_container.fetch("resources") == {
      "requests" => { "cpu" => "100m", "memory" => "2Gi" },
      "limits" => { "cpu" => "1", "memory" => "2Gi" }
    }

  abort "local: Java workloads must not depend on the old Compose bridge" if
    resources.key?(["NetworkPolicy", "simplematch-java-services-local-bridge"])
  {
    "postgres" => 5432,
    "redis" => 6379,
    "kafka" => 9092
  }.each do |service_name, port|
    service = resources.fetch(["Service", service_name])
    abort "local: #{service_name} must be an in-cluster SimpleMatch Service" unless
      service.dig("metadata", "labels", "app.kubernetes.io/part-of") == "simplematch" &&
        service.dig("spec", "ports", 0, "port") == port
  end

  %w[risk-service query-service].each do |deployment_name|
    deployment = resources.fetch(["Deployment", deployment_name])
    market_reference = deployment.fetch("spec").fetch("template").fetch("spec").fetch("volumes")
      .find { |volume| volume["name"] == "market-reference" }
    abort "local: #{deployment_name} must require the Market Reference artifact" unless
      market_reference == {
        "name" => "market-reference",
        "configMap" => {
          "name" => "matching-daily-artifact",
          "optional" => false
        }
      }
  end

  fix_spec = resources.fetch(["ConfigMap", "quickfix-gateway-fix-spec"])
  abort "local: QuickFIX FIX44 dictionary is missing" unless
    fix_spec.fetch("data").fetch("FIX44.xml").include?("<fix")
  quickfix_volumes = quickfix.fetch("spec").fetch("template").fetch("spec").fetch("volumes")
  abort "local: QuickFIX FIX44 dictionary volume is missing" unless
    quickfix_volumes.include?(
      "name" => "quickfix-fix-spec",
      "configMap" => {
        "name" => "quickfix-gateway-fix-spec",
        "items" => [{ "key" => "FIX44.xml", "path" => "FIX44.xml" }]
      }
    )
  abort "local: QuickFIX FIX44 dictionary mount is missing" unless
    quickfix_container.fetch("volumeMounts").include?(
      "name" => "quickfix-fix-spec",
      "mountPath" => "/workspace/fix-spec",
      "readOnly" => true
    )

  publisher = resources.fetch(["Deployment", "marketdata-publisher"])
  abort "local: superseded marketdata-publisher runtime must be disabled" unless
    publisher.fetch("spec").fetch("replicas") == 0
end

network_policy = resources.fetch(["NetworkPolicy", "simplematch-java-services"])
network_policy.fetch("spec").fetch("ingress").each do |rule|
  rule.fetch("from", []).each do |source|
    abort "#{overlay}: NetworkPolicy contains an unrestricted ingress pod selector" if source["podSelector"] == {}
  end
end
network_policy.fetch("spec").fetch("egress").each do |rule|
  rule.fetch("to", []).each do |destination|
    abort "#{overlay}: NetworkPolicy contains an unrestricted egress pod selector" if destination["podSelector"] == {}
  end
end

%w[account-service risk-service persistence market-data-projection marketdata-publisher marketdata-streamer query-service].each do |name|
  config = resources.fetch(["ConfigMap", "#{name}-config"], nil)
  next unless config
  application = config.fetch("data").fetch("application.yaml")
  abort "#{overlay}: #{name} ConfigMap contains postgres.dsn" if application.include?("postgres.dsn")
  abort "#{overlay}: #{name} ConfigMap contains a password" if application.match?(/password|sasl\.jaas/i)
end

if %w[staging production].include?(overlay)
  required_deployments.each do |name|
    image = resources.fetch(["Deployment", name]).fetch("spec").fetch("template").fetch("spec").fetch("containers").first.fetch("image")
    abort "#{overlay}: #{name} image is not digest pinned" unless image.match?(/@sha256:[0-9a-f]{64}\z/)
  end
  %w[account-service risk-service persistence market-data-projection query-service].each do |name|
    pod_spec = resources.fetch(["Deployment", name]).fetch("spec").fetch("template").fetch("spec")
    container = pod_spec.fetch("containers").first
    postgres_dsn = container.fetch("env").find { |entry| entry["name"] == "SIMPLEMATCH_POSTGRES_DSN" }
    abort "#{overlay}: #{name} has no secret-backed PostgreSQL DSN" unless postgres_dsn&.dig("valueFrom", "secretKeyRef")
    postgres_mount = container.fetch("volumeMounts").find { |entry| entry["name"] == "postgres-tls" }
    abort "#{overlay}: #{name} has no PostgreSQL CA mount" unless postgres_mount&.fetch("mountPath") == "/etc/simplematch/postgres-tls"
    postgres_volume = pod_spec.fetch("volumes").find { |entry| entry["name"] == "postgres-tls" }
    postgres_secret = postgres_volume&.dig("secret")
    abort "#{overlay}: #{name} does not require the PostgreSQL CA Secret" unless postgres_secret&.fetch("secretName") == "simplematch-postgres-tls" && postgres_secret.fetch("optional") == false
  end
  %w[account-service risk-service persistence market-data-projection query-service].each do |name|
    env = resources.fetch(["Deployment", name]).fetch("spec").fetch("template").fetch("spec").fetch("containers").first.fetch("env")
    protocol = env.find { |entry| entry["name"] == "SPRING_KAFKA_PROPERTIES_SECURITY_PROTOCOL" }
    abort "#{overlay}: #{name} does not require SASL_SSL" unless protocol.fetch("value") == "SASL_SSL"
  end
  streamer_env = resources.fetch(["Deployment", "marketdata-streamer"]).fetch("spec").fetch("template").fetch("spec").fetch("containers").first.fetch("env")
  streamer_protocol = streamer_env.find { |entry| entry["name"] == "SPRING_KAFKA_PROPERTIES_SECURITY_PROTOCOL" }
  abort "#{overlay}: marketdata-streamer does not require SASL_SSL" unless streamer_protocol.fetch("value") == "SASL_SSL"
  streamer_tls = streamer_env.find { |entry| entry["name"] == "SIMPLEMATCH_GRPC_SECURITY_TLS_ENABLED" }
  abort "#{overlay}: marketdata-streamer does not require gRPC TLS" unless streamer_tls.fetch("value") == "true"
  {
    "quickfix-gateway" => "quickfix-gateway-http-tls",
    "market-data-projection" => "market-data-projection-http-tls"
  }.each do |name, secret_name|
    workload = resources.fetch([name == "quickfix-gateway" ? "StatefulSet" : "Deployment", name])
    pod = workload.fetch("spec").fetch("template")
    container = pod.fetch("spec").fetch("containers").first
    ssl = container.fetch("env").find { |entry| entry["name"] == "SERVER_SSL_ENABLED" }
    abort "#{overlay}: #{name} operator endpoint is not HTTPS" unless ssl&.fetch("value") == "true"
    tls_volume = pod.fetch("spec").fetch("volumes").find { |volume| volume["name"] == "http-tls" }
    abort "#{overlay}: #{name} has no required operator HTTPS Secret" unless
      tls_volume&.dig("secret", "secretName") == secret_name && tls_volume.dig("secret", "optional") == false
    %w[startupProbe readinessProbe livenessProbe].each do |probe_name|
      probe = container.fetch(probe_name)
      abort "#{overlay}: #{name} #{probe_name} is not HTTPS" unless probe.dig("httpGet", "scheme") == "HTTPS"
    end
  end
  connector = resources.fetch(["Deployment", "kafka-connect"])
  connector_pod = connector.fetch("spec").fetch("template")
  connector_container = connector_pod.fetch("spec").fetch("containers").first
  abort "#{overlay}: Kafka Connect has no service account" unless connector_pod.fetch("spec").key?("serviceAccountName")
  abort "#{overlay}: Kafka Connect has no readiness probe" unless connector_container.key?("readinessProbe")
  abort "#{overlay}: Kafka Connect is not non-root" unless connector_container.fetch("securityContext").fetch("runAsNonRoot")
  abort "#{overlay}: Kafka Connect is not read-only root" unless connector_container.fetch("securityContext").fetch("readOnlyRootFilesystem")
  connector_env = connector_container.fetch("env").to_h { |entry| [entry.fetch("name"), entry] }
  %w[CONNECT_SECURITY_PROTOCOL CONNECT_SASL_MECHANISM CONNECT_SASL_JAAS_CONFIG CONNECT_SSL_TRUSTSTORE_PASSWORD].each do |name|
    abort "#{overlay}: Kafka Connect is missing #{name}" unless connector_env.key?(name)
  end
  abort "#{overlay}: Kafka Connect does not require the PostgreSQL CA" unless
    connector_pod.fetch("spec").fetch("volumes").any? { |volume| volume.dig("name") == "postgres-tls" && volume.dig("secret", "optional") == false }
  abort "#{overlay}: Kafka Connect NetworkPolicy missing" unless resources.key?(["NetworkPolicy", "simplematch-kafka-connect"])
  %w[account-service risk-service].each do |name|
    env = resources.fetch(["Deployment", name]).fetch("spec").fetch("template").fetch("spec").fetch("containers").first.fetch("env")
    tls = env.find { |entry| entry["name"] == "SIMPLEMATCH_GRPC_SECURITY_TLS_ENABLED" }
    abort "#{overlay}: #{name} does not require gRPC TLS" unless tls.fetch("value") == "true"
  end
  abort "#{overlay}: external NetworkPolicy missing" unless resources.key?(["NetworkPolicy", "simplematch-java-services-external"])
end

puts "Validated #{overlay}: #{documents.length} Kubernetes resources"
RUBY
done
