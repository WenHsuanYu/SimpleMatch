#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
runner="$script_dir/../run-failure-certification.sh"

fail() {
  printf 'Critical consumer verdict contract: %s\n' "$*" >&2
  exit 1
}

[[ -r "$runner" ]] || fail "runner is missing: $runner"
bash -n "$runner"

ruby - "$runner" <<'RUBY'
path = ARGV.fetch(0)
script = File.read(path, encoding: "UTF-8")
cleanup = script[/cleanup\(\) \{.*?^\}/m]
abort "cleanup function was not found" unless cleanup
main = script.split('current_stage="capture original workload configuration"', 2).last
abort "failure certification main sequence was not found" if main == script

pending = 'pending_pass_verdict="$evidence_dir/verdict.pending.json"'
abort "PASS verdict must be staged before cleanup" unless main.include?(pending)
abort "main sequence must not publish PASS verdict directly" if
  main.include?('>"$evidence_dir/verdict.json"')

restore_gateway = cleanup.index("restore_gateway_environment")
restore_workloads = cleanup.index("restore_workloads")
publish = cleanup.index('mv "$pending_pass_verdict" "$evidence_dir/verdict.json"')
abort "cleanup must restore Gateway configuration" unless restore_gateway
abort "cleanup must restore workloads" unless restore_workloads
abort "cleanup must publish the staged PASS verdict" unless publish
abort "PASS verdict must follow Gateway restoration" unless restore_gateway < publish
abort "PASS verdict must follow workload restoration" unless restore_workloads < publish
abort "restoration failure must be able to write FAIL verdict" unless
  cleanup.include?('write_failure_verdict "$status"')
abort "cleanup must remove a staged PASS before writing FAIL" unless
  cleanup.include?('rm -f "$pending_pass_verdict"')

puts "Critical consumer PASS verdict is published only after restoration."
RUBY
