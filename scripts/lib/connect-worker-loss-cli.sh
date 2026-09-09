#!/usr/bin/env bash

# CLI request Adapter for the focused Connect worker-loss scenario. It owns
# option syntax and basic scalar validation; execution and evidence semantics
# remain in connect-worker-loss-scenario.sh.

declare -gA CONNECT_WORKER_LOSS_REQUEST=()

connect_worker_loss_request_is_ready() {
  ((${#CONNECT_WORKER_LOSS_REQUEST[@]} > 0))
}

connect_worker_loss_usage() {
  cat <<'EOF_USAGE'
Usage:
  scripts/run-local-connect-worker-loss.sh \
    --namespace NAME --namespace-run-id ID [options]

Options:
  --namespace NAME       Existing disposable production-like namespace (required).
  --namespace-run-id ID  Exact value of the namespace run-id label (required).
  --context NAME         Kubernetes context (default: kind-simplematch-live).
  --cluster NAME         Canonical kind cluster name (default: simplematch-live).
  --retained-evidence-dir PATH
                         Source-aligned production-like evidence (default: SIMPLEMATCH_PRODUCTION_LIKE_EVIDENCE_DIR or out/certification/local-production-like).
  --evidence-dir PATH    Empty directory for this diagnostic report.
  --deadline-seconds N   Bounded fault/recovery deadline, at most 900 seconds (default: 600).
  --dry-run              Print the focused plan without changing state.

The diagnostic deletes exactly the Connect Pod that owns the Account connector
task, waits for a different task owner, then uses the shared CDC verifier to
prove one post-reassignment Account outbox transition reached account.lifecycle.
It is diagnostic evidence only; it is not a full-local certification PASS.
EOF_USAGE
}

connect_worker_loss_parse_args() {
  CONNECT_WORKER_LOSS_REQUEST=()
  local namespace="${SIMPLEMATCH_RESILIENCE_NAMESPACE:-}"
  local namespace_run_id="${SIMPLEMATCH_RESILIENCE_NAMESPACE_RUN_ID:-}"
  local cluster="${SIMPLEMATCH_KIND_CLUSTER_NAME:-simplematch-live}"
  local context="${SIMPLEMATCH_KUBE_CONTEXT:-kind-$cluster}"
  local context_explicit=false
  [[ -n "${SIMPLEMATCH_KUBE_CONTEXT:-}" ]] && context_explicit=true
  local retained="${SIMPLEMATCH_CONNECT_WORKER_LOSS_RETAINED_EVIDENCE_DIR:-${SIMPLEMATCH_PRODUCTION_LIKE_EVIDENCE_DIR:-out/certification/local-production-like}}"
  local evidence="${SIMPLEMATCH_CONNECT_WORKER_LOSS_EVIDENCE_DIR:-}"
  local deadline="${SIMPLEMATCH_CONNECT_WORKER_LOSS_DEADLINE_SECONDS:-$(connect_worker_loss_default_deadline_seconds)}"
  local dry_run=false

  while [[ $# -gt 0 ]]; do
    case "$1" in
      --namespace)
        [[ $# -ge 2 ]] || { connect_worker_loss_usage >&2; return 2; }
        namespace="$2"; shift 2 ;;
      --namespace-run-id)
        [[ $# -ge 2 ]] || { connect_worker_loss_usage >&2; return 2; }
        namespace_run_id="$2"; shift 2 ;;
      --context)
        [[ $# -ge 2 ]] || { connect_worker_loss_usage >&2; return 2; }
        context="$2"; context_explicit=true; shift 2 ;;
      --cluster)
        [[ $# -ge 2 ]] || { connect_worker_loss_usage >&2; return 2; }
        cluster="$2"; shift 2 ;;
      --retained-evidence-dir)
        [[ $# -ge 2 ]] || { connect_worker_loss_usage >&2; return 2; }
        retained="$2"; shift 2 ;;
      --evidence-dir)
        [[ $# -ge 2 ]] || { connect_worker_loss_usage >&2; return 2; }
        evidence="$2"; shift 2 ;;
      --deadline-seconds)
        [[ $# -ge 2 ]] || { connect_worker_loss_usage >&2; return 2; }
        deadline="$2"; shift 2 ;;
      --dry-run) dry_run=true; shift ;;
      --help|-h)
        connect_worker_loss_usage
        CONNECT_WORKER_LOSS_REQUEST=([help]=true)
        return 0
        ;;
      *)
        connect_worker_loss_usage >&2
        printf 'Connect worker-loss diagnostic: unknown option: %s\n' "$1" >&2
        return 2
        ;;
    esac
  done

  [[ "$context_explicit" == true ]] || context="kind-$cluster"

  [[ -n "$namespace" ]] || {
    printf 'Connect worker-loss diagnostic: --namespace is required\n' >&2
    return 2
  }
  [[ "$namespace" =~ ^[a-z0-9]([-a-z0-9]*[a-z0-9])?$ ]] || {
    printf 'Connect worker-loss diagnostic: namespace is not a valid Kubernetes name\n' >&2
    return 2
  }
  [[ -n "$namespace_run_id" && "$namespace_run_id" =~ ^[A-Za-z0-9._-]+$ ]] || {
    printf 'Connect worker-loss diagnostic: namespace run-id is invalid\n' >&2
    return 2
  }
  [[ "$deadline" =~ ^[1-9][0-9]*$ &&
    "$deadline" -le "$(connect_worker_loss_max_deadline_seconds)" ]] || {
    printf 'Connect worker-loss diagnostic: --deadline-seconds must be a positive integer no greater than %s\n' \
      "$(connect_worker_loss_max_deadline_seconds)" >&2
    return 2
  }

  CONNECT_WORKER_LOSS_REQUEST=(
    [cluster]="$cluster"
    [context]="$context"
    [namespace]="$namespace"
    [namespace_run_id]="$namespace_run_id"
    [retained_evidence_dir]="$retained"
    [evidence_dir]="$evidence"
    [deadline_seconds]="$deadline"
    [dry_run]="$dry_run"
    [run_id]="connect-worker-loss-$(date -u +%Y%m%dt%H%M%sz)-$$"
    [verifier_contract_script]="${SIMPLEMATCH_CDC_OBSERVER_CONTRACT_SCRIPT:-}"
  )
}
