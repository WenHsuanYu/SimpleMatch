#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
manifest="$repo_root/deploy/k8s/verification/risk-matching-replay-verifier-job.yaml"
orchestrator="$repo_root/scripts/run-risk-matching-restart-replay-e2e.sh"

for path in "$manifest" "$orchestrator"; do
  [[ -f "$path" ]] || {
    printf 'RM-1 restart/replay support file does not exist: %s\n' "$path" >&2
    exit 1
  }
done

bash -n "$orchestrator"

grep -Fq 'rollout restart deployment/risk-service deployment/kafka-connect' "$orchestrator" || {
  printf '%s\n' 'RM-1 restart/replay must cross both Risk and Kafka Connect Pod replacement.' >&2
  exit 1
}
grep -Fq 'wait_for_all_pods_replaced' "$orchestrator" || {
  printf '%s\n' 'RM-1 restart/replay must wait for stable Pod UID replacement after rollout availability.' >&2
  exit 1
}
grep -Fq 'matching-offsets-before-restart.json' "$orchestrator" || {
  printf '%s\n' 'RM-1 restart/replay must capture a Kafka boundary before restart.' >&2
  exit 1
}
grep -Fq 'matching-offsets-after-replay.json' "$orchestrator" || {
  printf '%s\n' 'RM-1 restart/replay must capture a Kafka boundary after equivalent replay.' >&2
  exit 1
}
grep -Fq 'capture_outbox_count' "$orchestrator" || {
  printf '%s\n' 'RM-1 restart/replay must prove the terminal replay keeps one outbox row.' >&2
  exit 1
}
grep -Fq 'baseline_evidence_dir/request.json' "$orchestrator" || {
  printf '%s\n' 'RM-1 restart/replay must compare replay request facts to baseline evidence.' >&2
  exit 1
}
if grep -Eq 'DELETE FROM|UPDATE risk_service\.(admission_journal|outbox)|connectors/.*/offsets' "$orchestrator"; then
  printf '%s\n' 'RM-1 restart/replay must not manufacture replay by mutating durable rows or offsets.' >&2
  exit 1
fi

ruby -ryaml - "$manifest" <<'RUBY'
manifest_path = ARGV.fetch(0)
documents = YAML.load_stream(File.read(manifest_path, encoding: "UTF-8")).compact
abort "RM-1 replay verifier manifest must contain exactly one document" unless documents.length == 1

job = documents.fetch(0)
abort "RM-1 replay verifier must be a batch/v1 Job" unless
  job["apiVersion"] == "batch/v1" && job["kind"] == "Job"
abort "RM-1 replay verifier Job name drifted" unless
  job.dig("metadata", "name") == "risk-matching-replay-verifier"

spec = job.fetch("spec")
abort "RM-1 replay verifier must fail closed without Job retries" unless spec["backoffLimit"] == 0
abort "RM-1 replay verifier must have a bounded active deadline" unless
  spec["activeDeadlineSeconds"].is_a?(Integer) && spec["activeDeadlineSeconds"].positive?

pod = spec.fetch("template").fetch("spec")
abort "RM-1 replay verifier must never restart in place" unless pod["restartPolicy"] == "Never"
abort "RM-1 replay verifier must not receive an API token" unless pod["automountServiceAccountToken"] == false
abort "RM-1 replay verifier must disable service links" unless pod["enableServiceLinks"] == false

containers = pod.fetch("containers")
abort "RM-1 replay verifier Job must contain one container" unless containers.length == 1
container = containers.fetch(0)
abort "RM-1 replay verifier must use the canonical verifier image" unless
  container["image"] == "simplematch/risk-matching-e2e-verifier:local"

expected_args = [
  "--artifact-path", "/etc/simplematch/market-reference/market_reference.json",
  "--checksum-path", "/etc/simplematch/market-reference/market_reference.sha256",
  "--trading-day", "$(SIMPLEMATCH_RM1_TRADING_DAY)",
  "--account-id", "$(SIMPLEMATCH_RM1_ACCOUNT_ID)",
  "--run-id", "$(SIMPLEMATCH_RM1_RUN_ID)",
  "--evidence-dir", "/tmp/evidence",
  "--timeout-seconds", "$(SIMPLEMATCH_RM1_TIMEOUT_SECONDS)",
  "--mode", "REPLAY"
]
abort "RM-1 replay verifier argument contract drifted" unless container["args"] == expected_args

abort "RM-1 replay verifier must read the dedicated immutable run ConfigMap" unless
  container.fetch("envFrom") == [
    {"configMapRef" => {"name" => "risk-matching-replay-run", "optional" => false}}
  ]

security = container.fetch("securityContext")
abort "RM-1 replay verifier must be non-root and read-only" unless
  security["allowPrivilegeEscalation"] == false &&
    security["readOnlyRootFilesystem"] == true &&
    security["runAsNonRoot"] == true &&
    security.dig("capabilities", "drop") == ["ALL"]

mounts = container.fetch("volumeMounts").to_h { |mount| [mount.fetch("name"), mount] }
abort "RM-1 replay verifier must mount Market Reference read-only" unless
  mounts.dig("market-reference", "readOnly") == true

volumes = pod.fetch("volumes").to_h { |volume| [volume.fetch("name"), volume] }
abort "RM-1 replay verifier must require matching-daily-artifact" unless
  volumes.dig("market-reference", "configMap", "name") == "matching-daily-artifact" &&
  volumes.dig("market-reference", "configMap", "optional") == false

puts "RM-1 restart/replay Kubernetes contract passed."
RUBY
