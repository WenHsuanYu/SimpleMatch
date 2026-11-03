#!/usr/bin/env ruby
# frozen_string_literal: true

require "json"
require "open3"
require "tmpdir"

root = File.expand_path("..", __dir__)
calculator = File.join(root, "scripts", "local-resource-budget.rb")
fixture = File.join(root, "scripts", "testdata", "local-resource-budget", "fixture.yaml")
host_bytes = 35 * 1024**3

Dir.mktmpdir("simplematch-resource-budget-") do |directory|
  report = File.join(directory, "report.json")
  command = ["ruby", calculator, "--manifest", fixture, "--host-memory-bytes", host_bytes.to_s,
             "--profile", "matching-fleet-only", "--report", report, "--check"]
  stdout, stderr, status = Open3.capture3(*command)
  abort "matching fleet fixture should fit: #{stdout}#{stderr}" unless status.success?
  matching_report = JSON.parse(File.read(report))
  abort "matching fleet selection drifted" unless
    matching_report.fetch("steady_memory_bytes") == (34 * 1024**3 + 248 * 1024**2) &&
    matching_report.fetch("bootstrap_memory_bytes") == 128 * 1024**2 &&
    matching_report.fetch("bootstrap_phase_memory_bytes") == (4 * 1024**3 + 256 * 1024**2) &&
    matching_report.fetch("peak_phase_memory_bytes") == matching_report.fetch("steady_memory_bytes") &&
    matching_report.fetch("requests_within_host_budget") == true

  full_command = command.dup
  full_command[full_command.index("matching-fleet-only")] = "full"
  stdout, stderr, status = Open3.capture3(*full_command)
  abort "full fixture must fail the 35 GiB host gate" if status.success?
  full_report = JSON.parse(File.read(report))
  abort "full fixture must report its excess" unless
    full_report.fetch("steady_memory_bytes") == (35 * 1024**3 + 248 * 1024**2) &&
    full_report.fetch("requests_within_host_budget") == false

  allowed_command = full_command.reject { |value| value == "--check" }
  stdout, stderr, status = Open3.capture3(*allowed_command)
  abort "an over-budget runtime attempt should remain allowed: #{stdout}#{stderr}" unless status.success?
  abort "the allowed attempt must warn about declared requests" unless stderr.include?("host budget")
  abort "the allowed attempt must retain the excess in evidence" unless
    JSON.parse(File.read(report)).fetch("requests_within_host_budget") == false

  midpoint_bytes = 35 * 1024**3 + 300 * 1024**2
  midpoint_command = full_command.dup
  midpoint_command[midpoint_command.index(host_bytes.to_s)] = midpoint_bytes.to_s
  stdout, stderr, status = Open3.capture3(*midpoint_command)
  abort "the full fixture should fit when each actual phase fits: #{stdout}#{stderr}" unless status.success?
  midpoint_report = JSON.parse(File.read(report))
  abort "the review envelope must not be used as the phase gate" unless
    midpoint_report.fetch("steady_plus_bootstrap_memory_bytes") > midpoint_bytes &&
    midpoint_report.fetch("peak_phase_memory_bytes") < midpoint_bytes

  malformed = File.join(directory, "malformed.yaml")
  File.write(malformed, File.read(fixture).sub("memory: 2Gi", "memory: invalid"))
  malformed_command = command.dup
  malformed_command[malformed_command.index(fixture)] = malformed
  _, stderr, status = Open3.capture3(*malformed_command)
  abort "malformed resource quantity must fail closed" if status.success? || !stderr.include?("invalid")

  larger_init = File.join(directory, "larger-init.yaml")
  File.write(larger_init, File.read(fixture).sub("requests: {cpu: 10m, memory: 32Mi}",
                                                  "requests: {cpu: 10m, memory: 2Gi}")
                                      .sub("limits: {cpu: 100m, memory: 64Mi}",
                                           "limits: {cpu: 100m, memory: 2Gi}"))
  init_command = command.reject { |value| value == "--check" }
  init_command[init_command.index(fixture)] = larger_init
  stdout, stderr, status = Open3.capture3(*init_command)
  abort "large init fixture failed: #{stdout}#{stderr}" unless status.success?
  init_report = JSON.parse(File.read(report))
  abort "init container peak was not multiplied by Kafka replicas" unless
    init_report.fetch("steady_memory_bytes") == matching_report.fetch("steady_memory_bytes") + 3 * 1024**3

end

puts "Local resource budget checks passed."
