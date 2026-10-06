#!/usr/bin/env ruby
# frozen_string_literal: true

require "json"
require "optparse"
require "yaml"

# Calculates aggregate host-level Kubernetes requests from one rendered local manifest.
class LocalResourceBudget
  WORKLOAD_KINDS = %w[Deployment StatefulSet Job].freeze
  UNSUPPORTED_WORKLOAD_KINDS = %w[Pod DaemonSet CronJob ReplicaSet ReplicationController].freeze
  MEMORY_SCALES = {
    "" => 1, "Ki" => 1024, "Mi" => 1024**2, "Gi" => 1024**3,
    "Ti" => 1024**4, "k" => 1000, "M" => 1000**2, "G" => 1000**3,
    "T" => 1000**4
  }.freeze
  CPU_SCALES = {"" => 1000, "m" => 1}.freeze

  def initialize(manifest, configuration, profile, host_memory_bytes)
    @profile = profile
    @host_memory_bytes = Integer(host_memory_bytes)
    raise ArgumentError, "host memory must be positive" unless @host_memory_bytes.positive?

    @configuration = JSON.parse(File.read(configuration))
    raise ArgumentError, "unsupported resource budget schema" unless @configuration.fetch("schema_version") == 1

    @selection = @configuration.fetch("profiles").fetch(profile)
    manifest_text = manifest == "-" ? $stdin.read : File.read(manifest)
    @documents = YAML.load_stream(manifest_text).compact
  end

  def report
    workloads = selected_workloads.map { |document| workload_request(document) }
    steady, bootstrap = workloads.partition { |workload| workload.fetch("phase") == "steady" }
    steady_memory = steady.sum { |workload| workload.fetch("memory_bytes") }
    bootstrap_memory = bootstrap.sum { |workload| workload.fetch("memory_bytes") }
    {
      "schema_version" => 1,
      "profile" => @profile,
      "host_memory_bytes" => @host_memory_bytes,
      "steady_memory_bytes" => steady_memory,
      "bootstrap_memory_bytes" => bootstrap_memory,
      "steady_plus_bootstrap_memory_bytes" => steady_memory + bootstrap_memory,
      "steady_cpu_millicores" => steady.sum { |workload| workload.fetch("cpu_millicores") },
      "bootstrap_cpu_millicores" => bootstrap.sum { |workload| workload.fetch("cpu_millicores") },
      "requests_within_host_budget" => steady_memory + bootstrap_memory <= @host_memory_bytes,
      "workloads" => workloads
    }
  end

  private

  def selected_workloads
    workloads = @documents.select do |document|
      kind = document.fetch("kind")
      raise ArgumentError, "unsupported workload kind: #{kind}" if UNSUPPORTED_WORKLOAD_KINDS.include?(kind)

      WORKLOAD_KINDS.include?(kind)
    end
    keyed = workloads.to_h do |document|
      ["#{document.fetch('kind')}/#{document.fetch('metadata').fetch('name')}", document]
    end
    raise ArgumentError, "rendered manifest contains no workloads" if keyed.empty?
    raise ArgumentError, "duplicate workload identity" unless keyed.length == workloads.length

    names = @selection == "all" ? keyed.keys : @selection
    raise ArgumentError, "invalid workload selection" unless names.is_a?(Array) && names.uniq.length == names.length

    names.sort.map { |name| keyed.fetch(name) }
  end

  def workload_request(document)
    kind = document.fetch("kind")
    name = document.fetch("metadata").fetch("name")
    spec = document.fetch("spec")
    replicas = kind == "Job" ? spec.fetch("parallelism", 1) : spec.fetch("replicas", 1)
    raise ArgumentError, "#{kind}/#{name} has invalid replica count" unless replicas.is_a?(Integer) && replicas.positive?

    pod = spec.fetch("template").fetch("spec")
    raise ArgumentError, "#{kind}/#{name} has pod-level resources; update the budget calculator" if pod.key?("resources")

    regular = pod.fetch("containers")
    initial = pod.fetch("initContainers", [])
    raise ArgumentError, "#{kind}/#{name} has no containers" if regular.empty?
    raise ArgumentError, "#{kind}/#{name} has restartable init containers" if
      initial.any? { |container| container["restartPolicy"] == "Always" }

    memory = pod_request(regular, initial, pod, "memory", MEMORY_SCALES)
    cpu = pod_request(regular, initial, pod, "cpu", CPU_SCALES)
    {
      "phase" => kind == "Job" ? "bootstrap" : "steady",
      "kind" => kind,
      "name" => name,
      "replicas" => replicas,
      "memory_bytes" => integral(memory * replicas, "#{kind}/#{name} memory"),
      "cpu_millicores" => integral(cpu * replicas, "#{kind}/#{name} CPU")
    }
  end

  def pod_request(regular, initial, pod, resource, scales)
    regular_request = regular.sum { |container| container_request(container, resource, scales) }
    init_request = initial.map { |container| container_request(container, resource, scales) }.max || 0
    overhead = pod.fetch("overhead", {}).fetch(resource, "0")
    [regular_request, init_request].max + quantity(overhead, scales)
  end

  def container_request(container, resource, scales)
    resources = container.fetch("resources", {})
    requested = resources.fetch("requests", {})[resource]
    limited = resources.fetch("limits", {})[resource]
    raise ArgumentError, "#{container.fetch('name')} has no #{resource} request or limit" if requested.nil? && limited.nil?

    quantity(requested || limited, scales)
  end

  def quantity(value, scales)
    match = /\A(\d+(?:\.\d+)?)([A-Za-z]*)\z/.match(value.to_s)
    raise ArgumentError, "invalid resource quantity: #{value.inspect}" unless match && scales.key?(match[2])

    Rational(match[1]) * scales.fetch(match[2])
  end

  def integral(value, description)
    raise ArgumentError, "#{description} is not an integral quantity" unless value.denominator == 1

    value.to_i
  end
end

if $PROGRAM_NAME == __FILE__
  options = {
    configuration: File.expand_path("../deploy/k8s/overlays/local/resource-budget.json", __dir__)
  }
  parser = OptionParser.new do |args|
    args.banner = "Usage: ruby scripts/local-resource-budget.rb --manifest FILE --profile NAME [options]"
    args.on("--manifest FILE") { |value| options[:manifest] = value }
    args.on("--profile NAME") { |value| options[:profile] = value }
    args.on("--host-memory-bytes BYTES", Integer) { |value| options[:host_memory_bytes] = value }
    args.on("--report FILE") { |value| options[:report] = value }
    args.on("--check") { options[:check] = true }
  end

  begin
    parser.parse!
    raise ArgumentError, "manifest and profile are required" unless options[:manifest] && options[:profile]
    raise ArgumentError, "unexpected arguments: #{ARGV.join(' ')}" unless ARGV.empty?

    configuration = JSON.parse(File.read(options.fetch(:configuration)))
    reference_bytes = configuration.fetch("reference_host_memory_gib") * 1024**3
    budget = LocalResourceBudget.new(options.fetch(:manifest), options.fetch(:configuration),
                                     options.fetch(:profile), options.fetch(:host_memory_bytes, reference_bytes)).report
    output = JSON.pretty_generate(budget) + "\n"
    options[:report] ? File.write(options[:report], output) : print(output)
    if options[:check] && !budget.fetch("requests_within_host_budget")
      warn "#{budget.fetch('profile')} requests #{budget.fetch('steady_plus_bootstrap_memory_bytes')} bytes " \
           "but the host budget is #{budget.fetch('host_memory_bytes')} bytes"
      exit 1
    end
  rescue ArgumentError, KeyError, OptionParser::ParseError, Psych::Exception => error
    warn error.message
    exit 2
  end
end
