#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
manifest="$repo_root/deploy/k8s/verification/risk-matching-e2e-verifier-job.yaml"

[[ -f "$manifest" ]] || {
  printf 'RM-1 verifier Job manifest does not exist: %s\n' "$manifest" >&2
  exit 1
}

dockerfile="$repo_root/deploy/docker/Dockerfile.risk-matching-e2e-verifier"
wrapper="$repo_root/deploy/docker/run-risk-matching-e2e-verifier"
orchestrator="$repo_root/scripts/run-risk-matching-command-e2e.sh"
cluster_data="$repo_root/scripts/end-to-end/critical-consumers/lib/cluster-data.sh"

for path in "$dockerfile" "$wrapper" "$orchestrator" "$cluster_data"; do
  [[ -f "$path" ]] || {
    printf 'RM-1 verifier support file does not exist: %s\n' "$path" >&2
    exit 1
  }
done

bash -n "$wrapper"
bash -n "$orchestrator"

grep -Fq 'critical-consumers/lib/cluster-data.sh' "$orchestrator" || {
  printf '%s\n' 'RM-1 orchestrator must decode the mounted artifact through the shared ConfigMap seam.' >&2
  exit 1
}

(
  # shellcheck source=scripts/end-to-end/critical-consumers/lib/cluster-data.sh
  source "$cluster_data"
  binary_payload='{"marketSnapshot":{"instruments":[{"venueMic":"XTAI","symbol":"2330","eligibility":"ELIGIBLE","referencePriceUnits":10000000,"lowerPriceLimitUnits":9000000,"upperPriceLimitUnits":11000000}]}}'

  for configmap_field in binaryData data; do
    kns() {
      [[ "${1:-}" == get && "${2:-}" == configmap/matching-daily-artifact ]] || return 1
      if [[ "$configmap_field" == binaryData ]]; then
        jq -n --arg payload "$binary_payload" \
          '{binaryData:{"market_reference.json":($payload|@base64)}}'
      else
        jq -n --arg payload "$binary_payload" \
          '{data:{"market_reference.json":$payload}}'
      fi
    }

    kns get configmap/matching-daily-artifact -o json >/dev/null
    decoded_artifact="$(decode_configmap_file matching-daily-artifact market_reference.json)"
    jq -e '.marketSnapshot.instruments[0].referencePriceUnits == 10000000' \
      <<<"$decoded_artifact" >/dev/null || {
      printf 'RM-1 shared ConfigMap seam must decode %s market_reference.json.\n' \
        "$configmap_field" >&2
      exit 1
    }
  done
)

grep -Fq "command_id=\"\$(jq -r '.commandId' \"\$evidence_dir/request.json\")\"" \
  "$orchestrator" || {
    printf '%s\n' 'RM-1 orchestrator must derive commandId from the submitted request evidence.' >&2
    exit 1
  }
if grep -Fq 'response.json' "$orchestrator"; then
  printf '%s\n' 'RM-1 orchestrator must not depend on a synchronous response.json artifact.' >&2
  exit 1
fi
grep -Fq 'admission-outcome.json' "$orchestrator" || {
  printf '%s\n' 'RM-1 orchestrator must require normalized terminal Admission evidence.' >&2
  exit 1
}
grep -Fq 'evidence directory must be empty before verification' "$orchestrator" || {
  printf '%s\n' 'RM-1 orchestrator must fail closed when the evidence directory is non-empty.' >&2
  exit 1
}
grep -Fq -- '--verifier-image' "$orchestrator" || {
  printf '%s\n' 'RM-1 orchestrator must accept the retained verifier image reference.' >&2
  exit 1
}
grep -Fq -- '--retained-evidence-dir' "$orchestrator" || {
  printf '%s\n' 'RM-1 orchestrator must require retained provenance before creating a verifier Job.' >&2
  exit 1
}
grep -Fq 'simplematch_verify_kind_loaded_verifier_image_execution' "$orchestrator" || {
  printf '%s\n' 'RM-1 orchestrator must probe the retained kind-loaded image before Job creation.' >&2
  exit 1
}
grep -Fq 'simplematch_verify_kind_loaded_verifier_image_execution' \
  "$repo_root/scripts/lib/local-certification-provenance.sh" || {
  printf '%s\n' 'RM-1 provenance module must own the kind-loaded execution probe.' >&2
  exit 1
}
grep -Fq 'ctr -n k8s.io run --rm' \
  "$repo_root/scripts/lib/local-certification-provenance.sh" || {
  printf '%s\n' 'RM-1 kind-loaded probe must execute the image through node containerd.' >&2
  exit 1
}
grep -Fq -- '--side' "$orchestrator" || {
  printf '%s\n' 'RM-1 orchestrator must select BUY or SELL for the public fixture.' >&2
  exit 1
}
grep -Fq 'simplematch_render_verifier_helper_manifest' "$orchestrator" || {
  printf '%s\n' 'RM-1 orchestrator must render the verifier Job with its retained image reference.' >&2
  exit 1
}
grep -Fq "DATE '\$trading_day'" "$orchestrator" || {
  printf '%s\n' 'RM-1 Account fixture must use the explicit certification trading day.' >&2
  exit 1
}
grep -Fq 'selected Market Reference symbol is not a safe alphanumeric identifier' "$orchestrator" || {
  printf '%s\n' 'RM-1 fixture must validate the selected symbol before SQL construction.' >&2
  exit 1
}
grep -Fq 'selected Market Reference board lot quantity is not a safe positive integer' "$orchestrator" || {
  printf '%s\n' 'RM-1 fixture must validate the selected quantity before SQL construction.' >&2
  exit 1
}
if grep -Fq 'account_limit_day' "$orchestrator"; then
  printf '%s\n' 'RM-1 Account fixture must not derive its limit day from the host wall clock.' >&2
  exit 1
fi

invalid_account_evidence="$(mktemp -d)"
trap 'rm -rf -- "$invalid_account_evidence"' EXIT
if invalid_account_output="$($orchestrator \
  --namespace simplematch-test \
  --trading-day 2026-08-27 \
  --evidence-dir "$invalid_account_evidence" \
  --account-id not-a-uuid 2>&1)"; then
  printf '%s\n' 'RM-1 orchestrator accepted an invalid deterministic account UUID.' >&2
  exit 1
fi
grep -Fq -- '--account-id must be a canonical lowercase UUID' \
  <<<"$invalid_account_output" || {
    printf '%s\n' 'RM-1 orchestrator did not fail closed on an invalid account UUID.' >&2
    exit 1
  }

image_inventory="$("$repo_root/scripts/build-local-images.sh" --list)"
grep -Fq \
  'verification|risk-matching-e2e-verifier|deploy/docker/Dockerfile.risk-matching-e2e-verifier|simplematch/risk-matching-e2e-verifier:local' \
  <<<"$image_inventory" || {
    printf '%s\n' 'RM-1 verifier image is missing from the canonical local image inventory.' >&2
    exit 1
  }

verifier_build_dry_run="$(
  "$repo_root/scripts/build-local-images.sh" --dry-run --service risk-matching-e2e-verifier
)"
grep -Fq 'Dockerfile.risk-matching-e2e-verifier' <<<"$verifier_build_dry_run" || {
  printf '%s\n' 'RM-1 verifier image build does not use its dedicated Dockerfile.' >&2
  exit 1
}

grep -Fq ':tools:risk-matching-e2e-verifier:installDist' "$dockerfile" || {
  printf '%s\n' 'RM-1 verifier image does not build the application distribution ahead of runtime.' >&2
  exit 1
}
if grep -Fq 'gradlew' "$wrapper"; then
  printf '%s\n' 'RM-1 verifier runtime wrapper must not invoke Gradle.' >&2
  exit 1
fi

ruby -ryaml - "$manifest" <<'RUBY'
manifest_path = ARGV.fetch(0)
documents = YAML.load_stream(File.read(manifest_path, encoding: "UTF-8")).compact
abort "RM-1 verifier manifest must contain exactly one document" unless documents.length == 1

job = documents.fetch(0)
abort "RM-1 verifier resource must be a batch/v1 Job" unless
  job["apiVersion"] == "batch/v1" && job["kind"] == "Job"
abort "RM-1 verifier Job must use the canonical name" unless
  job.dig("metadata", "name") == "risk-matching-e2e-verifier"

labels = job.dig("metadata", "labels") || {}
abort "RM-1 verifier Job must be owned by SimpleMatch verification" unless
  labels["app.kubernetes.io/name"] == "risk-matching-e2e-verifier" &&
    labels["app.kubernetes.io/component"] == "verification" &&
    labels["app.kubernetes.io/part-of"] == "simplematch"

spec = job.fetch("spec")
abort "RM-1 verifier Job must fail closed without Job retries" unless spec["backoffLimit"] == 0
abort "RM-1 verifier Job must have a bounded active deadline" unless
  spec["activeDeadlineSeconds"].is_a?(Integer) && spec["activeDeadlineSeconds"].positive?

pod = spec.fetch("template").fetch("spec")
abort "RM-1 verifier must never restart in-place" unless pod["restartPolicy"] == "Never"
abort "RM-1 verifier must not receive an API token" unless pod["automountServiceAccountToken"] == false
abort "RM-1 verifier must disable injected service-link environment" unless pod["enableServiceLinks"] == false

pod_security = pod.fetch("securityContext")
abort "RM-1 verifier Pod must use RuntimeDefault seccomp" unless
  pod_security.dig("seccompProfile", "type") == "RuntimeDefault"
abort "RM-1 verifier Pod must run as uid/gid 10001" unless
  pod_security["runAsNonRoot"] == true &&
    pod_security["runAsUser"] == 10001 &&
    pod_security["runAsGroup"] == 10001 &&
    pod_security["fsGroup"] == 10001

containers = pod.fetch("containers")
abort "RM-1 verifier Job must contain exactly one container" unless containers.length == 1
container = containers.fetch(0)
abort "RM-1 verifier container name changed unexpectedly" unless container["name"] == "verifier"
abort "RM-1 verifier must use the dedicated local image" unless
  container["image"] == "simplematch/risk-matching-e2e-verifier:local"
abort "RM-1 verifier must not override the image entrypoint with a shell" if container.key?("command")

expected_args = [
  "--artifact-path", "/etc/simplematch/market-reference/market_reference.json",
  "--checksum-path", "/etc/simplematch/market-reference/market_reference.sha256",
  "--trading-day", "$(SIMPLEMATCH_RM1_TRADING_DAY)",
  "--account-id", "$(SIMPLEMATCH_RM1_ACCOUNT_ID)",
  "--side", "$(SIMPLEMATCH_RM1_SIDE)",
  "--run-id", "$(SIMPLEMATCH_RM1_RUN_ID)",
  "--evidence-dir", "/tmp/evidence",
  "--timeout-seconds", "$(SIMPLEMATCH_RM1_TIMEOUT_SECONDS)"
]
abort "RM-1 verifier argument contract drifted" unless container["args"] == expected_args

env_from = container.fetch("envFrom")
abort "RM-1 verifier must read exactly one run ConfigMap" unless env_from == [
  {
    "configMapRef" => {
      "name" => "risk-matching-e2e-run",
      "optional" => false
    }
  }
]

security = container.fetch("securityContext")
abort "RM-1 verifier container must be non-root with a read-only root filesystem" unless
  security["allowPrivilegeEscalation"] == false &&
    security["readOnlyRootFilesystem"] == true &&
    security["runAsNonRoot"] == true &&
    security["runAsUser"] == 10001 &&
    security["runAsGroup"] == 10001 &&
    security.dig("capabilities", "drop") == ["ALL"]

expected_resources = {
  "requests" => { "cpu" => "250m", "memory" => "512Mi" },
  "limits" => { "cpu" => "2", "memory" => "2Gi" }
}
abort "RM-1 verifier resource budget drifted" unless container["resources"] == expected_resources

mounts = container.fetch("volumeMounts").to_h { |mount| [mount.fetch("name"), mount] }
abort "RM-1 verifier must have a writable /tmp volume" unless
  mounts["runtime-tmp"] == { "name" => "runtime-tmp", "mountPath" => "/tmp" }
abort "RM-1 verifier must mount Market Reference read-only" unless
  mounts["market-reference"] == {
    "name" => "market-reference",
    "mountPath" => "/etc/simplematch/market-reference",
    "readOnly" => true
  }

volumes = pod.fetch("volumes").to_h { |volume| [volume.fetch("name"), volume] }
abort "RM-1 verifier runtime /tmp must be ephemeral" unless
  volumes["runtime-tmp"] == { "name" => "runtime-tmp", "emptyDir" => {} }

market_reference = volumes.fetch("market-reference")
abort "RM-1 verifier must require matching-daily-artifact" unless
  market_reference.dig("configMap", "name") == "matching-daily-artifact" &&
    market_reference.dig("configMap", "optional") == false
items = market_reference.dig("configMap", "items")
abort "RM-1 verifier Market Reference keys drifted" unless items == [
  { "key" => "market_reference.json", "path" => "market_reference.json" },
  { "key" => "market_reference.sha256", "path" => "market_reference.sha256" }
]

puts "RM-1 verifier Kubernetes Job manifest contract passed."
RUBY
