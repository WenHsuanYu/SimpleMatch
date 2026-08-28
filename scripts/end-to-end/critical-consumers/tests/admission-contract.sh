#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd -- "$script_dir/../../../.." && pwd)"
cluster_module="$script_dir/../lib/cluster-data.sh"
recovery_module="$script_dir/../lib/failure-recovery.sh"
quickfix_build="$repo_root/services/quickfix-gateway/build.gradle.kts"
prepared_test="$repo_root/services/quickfix-gateway/src/test/java/com/simplematch/quickfixgateway/fix/QuickFixPreparedSubmissionLiveCertificationTest.java"
temporary_directory="$(mktemp -d "${TMPDIR:-/tmp}/simplematch-admission-contract.XXXXXX")"
trap 'rm -rf "$temporary_directory"' EXIT

fail() {
  printf 'QuickFIX admission contract: %s\n' "$*" >&2
  exit 1
}

bash -n "$cluster_module"
bash -n "$recovery_module"

ruby - "$quickfix_build" <<'RUBY'
path = ARGV.fetch(0)
build = File.read(path, encoding: "UTF-8")
helper = build[/fun Test\.configureLiveCertification\(testClassName: String\) \{(.*?)^\}/m, 1]
abort "QuickFIX live certification helper is missing" unless helper
abort "QuickFIX live certification tasks must disable Gradle state tracking" unless
  helper.include?("doNotTrackState(")

%w[
  liveCertificationTest
  retainedSessionCertificationTest
  preparedSubmissionCertificationTest
].each do |task_name|
  body = build[/tasks\.register<Test>\("#{Regexp.escape(task_name)}"\) \{(.*?)^\}/m, 1]
  abort "#{task_name} is missing" unless body
  abort "#{task_name} must use the untracked live certification configuration" unless
    body.include?("configureLiveCertification(")
end

simulator = build[/tasks\.register<Test>\("certificationTest"\) \{(.*?)^\}/m, 1]
abort "certificationTest is missing" unless simulator
abort "simulator certification should retain normal Gradle state tracking" if
  simulator.include?("configureLiveCertification(") || simulator.include?("doNotTrackState(")
RUBY

ruby - "$prepared_test" <<'RUBY'
path = ARGV.fetch(0)
source = File.read(path, encoding: "UTF-8")
abort "prepared FIX client must retain structured ExecutionReport evidence" unless
  source.include?("writeEvidence(evidencePath, report, sentAtEpochMs);")
abort "prepared FIX client must not classify admission semantics inside JUnit" if
  source.include?("assertThat(actualExecType)") || source.include?("assertThat(actualOrdStatus)")
RUBY

rendered="$temporary_directory/local.yaml"
kubectl kustomize "$repo_root/deploy/k8s/overlays/local" \
  --load-restrictor LoadRestrictionsNone >"$rendered"

ruby -r yaml - "$rendered" <<'RUBY'
path = ARGV.fetch(0)
documents = YAML.load_stream(File.read(path, encoding: "UTF-8")).compact
resources = documents.to_h do |document|
  [[document.fetch("kind"), document.fetch("metadata").fetch("name")], document]
end

quickfix = resources.fetch(["StatefulSet", "quickfix-gateway"])
labels = quickfix.fetch("spec").fetch("template").fetch("metadata").fetch("labels")
abort "QuickFIX Pod must identify itself as part of simplematch" unless
  labels["app.kubernetes.io/part-of"] == "simplematch"

environment = quickfix.fetch("spec").fetch("template").fetch("spec").fetch("containers").first.fetch("env")
  .to_h { |entry| [entry.fetch("name"), entry] }
trading_day_reference = environment.fetch("SIMPLEMATCH_TRADING_DAY").fetch("valueFrom").fetch("configMapKeyRef")
abort "QuickFIX trading day must share matching-session-config authority" unless
  trading_day_reference == { "name" => "matching-session-config", "key" => "trading_day" }

network_policy = resources.fetch(["NetworkPolicy", "simplematch-java-services"])
authorized = network_policy.fetch("spec").fetch("ingress").any? do |rule|
  permits_source = rule.fetch("from", []).any? do |source|
    source.dig("podSelector", "matchLabels", "app.kubernetes.io/part-of") == "simplematch"
  end
  permits_risk_grpc = rule.fetch("ports", []).any? do |port|
    port["protocol"] == "TCP" && port["port"] == 50052
  end
  permits_source && permits_risk_grpc
end
abort "Risk ingress must authorize SimpleMatch Pods on TCP 50052" unless authorized
RUBY

(
  # shellcheck source=scripts/end-to-end/critical-consumers/lib/cluster-data.sh
  source "$cluster_module"

  evidence_dir="$temporary_directory/evidence"
  mkdir -p "$evidence_dir/submission"

  artifact='{
    "metadata": {
      "tradingDay": "2026-08-26",
      "routingAlgorithmVersion": "partition-v1"
    },
    "marketSnapshot": {
      "instruments": [
        {
          "venueMic": "ROCO",
          "symbol": "1240",
          "eligibility": "ELIGIBLE",
          "referencePriceUnits": 569000,
          "marketRuleId": "roco-rule"
        },
        {
          "venueMic": "XTAI",
          "symbol": "2330",
          "eligibility": "ELIGIBLE",
          "referencePriceUnits": 10000000,
          "marketRuleId": "xtai-rule"
        }
      ]
    },
    "marketRules": {
      "rules": [
        {"ruleId": "roco-rule", "boardLotShares": 1000},
        {"ruleId": "xtai-rule", "boardLotShares": 1000}
      ]
    }
  }'

  die() {
    fail "$*"
  }

  current_taipei_calendar_day() {
    printf '%s\n' '2026-08-26'
  }

  decode_configmap_file() {
    local configmap="$1"
    local key="$2"
    case "$configmap:$key" in
      matching-daily-artifact:market_reference.json)
        printf '%s\n' "$artifact"
        ;;
      matching-daily-artifact:market_reference.sha256)
        printf '%064d\n' 0
        ;;
      quickfix-gateway-config:application.yaml)
        cat <<'YAML'
simplematch:
  quickfix-gateway:
    ingress:
      venue-mic: XTAI
YAML
        ;;
      *)
        return 1
        ;;
    esac
  }

  kns() {
    [[ "$1" == get && "$2" == configmap && "$3" == matching-session-config && "$4" == -o ]] ||
      return 1
    case "$5" in
      'jsonpath={.data.trading_day}')
        printf '%s\n' '2026-08-26'
        ;;
      'jsonpath={.data.trading_session_id}')
        printf '%s\n' 'session-20260826'
        ;;
      'jsonpath={.data.matching_image_digest}')
        printf '%s\n' 'sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'
        ;;
      'jsonpath={.immutable}')
        printf '%s\n' 'true'
        ;;
      *)
        return 1
        ;;
    esac
  }

  select_market_input

  [[ "$gateway_venue_mic" == 'XTAI' ]] ||
    fail 'certification did not resolve the deployed QuickFIX ingress venue'
  [[ "$venue_mic" == 'XTAI' && "$symbol" == '2330' ]] ||
    fail 'certification selected an instrument from a different venue'
  jq -e '.venueMic == "XTAI" and .symbol == "2330"' \
    "$evidence_dir/submission/selected-instrument.json" >/dev/null ||
    fail 'selected-instrument evidence does not preserve the Gateway venue identity'
)

(
  # shellcheck source=scripts/end-to-end/critical-consumers/lib/cluster-data.sh
  source "$cluster_module"
  reason="$temporary_directory/mutable-session-reason.txt"

  die() {
    printf '%s\n' "$*" >"$reason"
    return 1
  }

  kns() {
    [[ "${5:-}" == 'jsonpath={.immutable}' ]] && printf '%s\n' 'false'
  }

  if select_market_input; then
    fail 'mutable matching-session-config must be rejected'
  fi
  grep -F 'matching-session-config must be immutable' "$reason" >/dev/null ||
    fail 'mutable session identity must retain a specific failure reason'
)

(
  # shellcheck source=scripts/end-to-end/critical-consumers/lib/cluster-data.sh
  source "$cluster_module"
  reason="$temporary_directory/trading-day-reason.txt"

  die() {
    printf '%s\n' "$*" >"$reason"
    return 1
  }

  current_taipei_calendar_day() {
    printf '%s\n' '2026-08-27'
  }

  unset SIMPLEMATCH_CERTIFICATION_TRADING_DAY
  require_live_fix_trading_day '2026-08-27' ||
    fail 'current Taipei trading day must remain the default'

  if require_live_fix_trading_day '2026-08-26'; then
    fail 'stale retained namespace trading day must be rejected by default'
  fi
  grep -F \
    'retained namespace trading day 2026-08-26 does not match expected certification trading day 2026-08-27' \
    "$reason" >/dev/null ||
    fail 'default trading-day mismatch must retain configured and expected dates'

  export SIMPLEMATCH_CERTIFICATION_TRADING_DAY=2026-08-26
  require_live_fix_trading_day '2026-08-26' ||
    fail 'explicit historical certification trading day must be accepted'

  if require_live_fix_trading_day '2026-08-27'; then
    fail 'retained namespace must still match the explicit certification trading day'
  fi
  grep -F \
    'retained namespace trading day 2026-08-27 does not match expected certification trading day 2026-08-26' \
    "$reason" >/dev/null ||
    fail 'explicit trading-day mismatch must retain configured and expected dates'

  export SIMPLEMATCH_CERTIFICATION_TRADING_DAY=20260826
  if require_live_fix_trading_day '2026-08-26'; then
    fail 'malformed explicit certification trading day must be rejected'
  fi
  grep -F \
    'SIMPLEMATCH_CERTIFICATION_TRADING_DAY must use YYYY-MM-DD: 20260826' \
    "$reason" >/dev/null ||
    fail 'malformed certification trading day must retain the invalid value'
)

(
  # shellcheck source=scripts/end-to-end/critical-consumers/lib/failure-recovery.sh
  source "$recovery_module"
  unknown="$temporary_directory/unknown.json"
  accepted="$temporary_directory/accepted.json"
  rejected="$temporary_directory/rejected.json"
  unexpected="$temporary_directory/unexpected.json"
  reason="$temporary_directory/reason.txt"

  printf '%s\n' '{"execId":"UN-123","execType":"A","ordStatus":"A","text":"SYSTEM_ERROR: order outcome is pending confirmation; no client action is required"}' >"$unknown"
  printf '%s\n' '{"execId":"E-123","execType":"A","ordStatus":"A","text":""}' >"$accepted"
  printf '%s\n' '{"execId":"R-123","execType":"8","ordStatus":"8","text":"validation rejected"}' >"$rejected"
  printf '%s\n' '{"execId":"X-123","execType":"0","ordStatus":"0","text":"unexpected lifecycle"}' >"$unexpected"

  die() {
    printf '%s\n' "$*" >"$reason"
    return 1
  }

  fix_submission_outcome_is_unknown "$unknown" ||
    fail 'UN-prefixed FIX evidence must be classified as UNKNOWN'
  if fix_submission_outcome_is_unknown "$accepted"; then
    fail 'accepted FIX evidence must not be classified as UNKNOWN'
  fi

  require_fix_submission_accepted "$accepted" ||
    fail 'A/A FIX evidence must be accepted by the runner'

  if require_fix_submission_accepted "$unknown"; then
    fail 'UNKNOWN FIX evidence must not be accepted by the runner'
  fi
  grep -F 'FIX Risk admission outcome remained UNKNOWN' "$reason" >/dev/null ||
    fail 'UNKNOWN FIX evidence must retain a specific failure reason'

  if require_fix_submission_accepted "$rejected"; then
    fail 'rejected FIX evidence must not be accepted by the runner'
  fi
  grep -F 'ExecType=8 OrdStatus=8 Text=validation rejected' "$reason" >/dev/null ||
    fail 'rejected FIX evidence must retain FIX status and Text(58)'

  if require_fix_submission_accepted "$unexpected"; then
    fail 'unexpected lifecycle FIX evidence must not be accepted by the runner'
  fi
  grep -F 'ExecType=0 OrdStatus=0 Text=unexpected lifecycle' "$reason" >/dev/null ||
    fail 'unexpected FIX evidence must retain FIX status and Text(58)'
)

(
  # shellcheck source=scripts/end-to-end/critical-consumers/lib/failure-recovery.sh
  source "$recovery_module"
  pod_revision='quickfix-gateway-new'
  kns() {
    if [[ "$1" == get && "$2" == statefulset/quickfix-gateway ]]; then
      printf '%s' 'quickfix-gateway-new'
      return 0
    fi
    if [[ "$1" == get && "$2" == pods ]]; then
      jq -n --arg revision "$pod_revision" '{items:[{
        metadata:{labels:{"controller-revision-hash":$revision}},
        status:{conditions:[{type:"Ready",status:"True"}]}
      }]}'
      return 0
    fi
    return 1
  }

  statefulset_revision_converged quickfix-gateway 1 ||
    fail 'restoration must accept a Ready Pod on updateRevision'
  pod_revision='quickfix-gateway-old'
  if statefulset_revision_converged quickfix-gateway 1; then
    fail 'restoration must reject a Ready Pod on an older revision'
  fi
)

printf 'QuickFIX admission deployment, evidence, recovery, and live-task contracts are valid.\n'
