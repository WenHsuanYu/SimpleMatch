#!/usr/bin/env bash

# Connect data interpretation belongs to the shared Java recovery executable.

connect_worker_loss_report_schema_version() {
  printf '%s\n' 3
}

connect_worker_loss_max_deadline_seconds() {
  printf '%s\n' 900
}

connect_worker_loss_default_deadline_seconds() {
  printf '%s\n' 600
}

connect_worker_loss_setup_deadline_seconds() {
  printf '%s\n' 300
}

connect_worker_loss_target_identity() {
  resilience_verify_evidence connect owner "$1" "$2" "$3" >/dev/null
}

connect_worker_loss_assert_reassignment() {
  resilience_verify_evidence connect reassigned "$1" "$2" "$3" "$4" >/dev/null
}

connect_worker_loss_report_is_passed() {
  local report_file="$1" directory
  directory="$(dirname -- "$report_file")"
  cdc_validate_probe "$directory/account-outbox-probe.json" || return 1
  cdc_validate_publication_evidence "$directory/account-publication.json" || return 1
  resilience_verify_evidence connect verify "$report_file" >/dev/null
}
