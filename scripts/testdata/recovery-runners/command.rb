#!/usr/bin/env ruby
# frozen_string_literal: true

# External command doubles for the real diagnostic. Unknown calls fail closed.
require 'json'
root = ENV.fetch('RECOVERY_TEST_ROOT')
fixtures = ENV.fetch('RECOVERY_TEST_DATA')
mode = ENV.fetch('RECOVERY_TEST_MODE')
tool = File.basename($PROGRAM_NAME)
args = ARGV.join(' ')
File.open("#{root}/calls", 'a') { |f| f.puts("#{tool}:#{args}") }
def emit(value)
  puts JSON.generate(value)
end
def ready_pod(name)
  { metadata: { name: name }, status: { phase: 'Running',
    conditions: [{ type: 'Ready', status: 'True' }] } }
end
def file_json(path)
  JSON.parse(File.read(path))
end
def read_fixture(fixtures, name)
  File.read("#{fixtures}/#{name}.json")
end
case tool
when 'kind'
  abort 'unexpected kind call' unless ARGV == %w[get clusters]
  puts 'simplematch-live'
when 'docker'
  if %w[info].include?(ARGV.first) || args == 'system df'
    exit 0
  elsif ARGV[0] == 'exec' && ARGV[2] == 'crictl'
    emit(status: { id: "sha256:#{'a' * 64}" })
  elsif ARGV[0] == 'exec' && ARGV[2] == 'ctr'
    exit 0
  else
    abort "unexpected Docker call: #{args}"
  end
when 'curl'
  if mode == 'placement'
    File.write(ARGV[ARGV.index('-o') + 1], '{"status":"UP"}')
    print '200'
    exit 0
  end
  stage = File.exist?("#{root}/deleted") ? 'after-reassignment' : 'before'
  puts read_fixture(fixtures, "connect-status-#{stage}")
when 'kubectl'
  if args.include?('config get-contexts')
    puts 'kind-simplematch-live'
  elsif args.include?("get --raw=/readyz?verbose")
    puts 'readyz check passed'
  elsif args.include?('get pods -n kube-system')
    emit(items: %w[etcd-local kube-controller-manager-local kube-scheduler-local].map { |n| ready_pod(n) })
  elsif args.include?('get events -n kube-system')
    emit(items: [])
  elsif args.include?('get nodes -o json')
    emit(items: %w[simplematch-live-worker simplematch-live-worker2 simplematch-live-worker3].each_with_index.map do |name, index|
      ready_pod(name).merge(metadata: { name: name, labels: {
        'simplematch.io/node-pool' => 'local-resilience', 'simplematch.io/worker-slot' => index.to_s } })
    end + [ready_pod('simplematch-live-control-plane').merge(metadata: {name: 'simplematch-live-control-plane', labels: {'node-role.kubernetes.io/control-plane' => ''}})])
  elsif args.include?('get node ')
    puts '0'
  elsif args.include?('rollout status')
    exit 0
  elsif args.include?('get deployment query-service')
    doc = file_json("#{fixtures}/deployment.json")
    doc['spec']['template']['spec']['containers'][0]['image'] = ENV.fetch('RECOVERY_TEST_QUERY_IMAGE')
    emit(doc)
  elsif args.include?('get pods -l app.kubernetes.io/name=query-service')
    doc = file_json("#{fixtures}/pods.json")
    doc['items'].each do |pod|
      pod['status']['containerStatuses'][0]['imageID'] = "docker-pullable://#{ENV.fetch('RECOVERY_TEST_QUERY_IMAGE')}"
    end
    emit(doc)
  elsif args.include?('get deployment redis')
    replicas = File.exist?("#{root}/redis-down") ? 0 : 1
    emit(metadata: {name: 'redis', uid: 'redis-deployment'}, spec: {replicas: replicas},
      status: {readyReplicas: replicas, availableReplicas: replicas, updatedReplicas: replicas})
  elsif args.include?('get pods -l app.kubernetes.io/name=redis')
    emit(items: File.exist?("#{root}/redis-down") ? [] : [ready_pod('redis-0')])
  elsif args.include?('scale deployment/redis')
    if args.include?('--replicas=0')
      File.write("#{root}/redis-down", '')
    else
      File.delete("#{root}/redis-down")
      File.write("#{root}/redis-restored", '')
    end
  elsif args.include?('port-forward --address 127.0.0.1 pod/')
    trap('TERM') { File.write("#{root}/tunnel-closed", ''); exit 0 }
    puts "Forwarding from 127.0.0.1:#{ENV.fetch('RECOVERY_TEST_HTTP_PORT')} -> 8086"
    $stdout.flush
    sleep
  elsif args.include?('get deployment kafka-connect')
    puts read_fixture(fixtures, 'connect-deployment')
  elsif args.include?('get job ')
    name = ARGV[ARGV.index('job') + 1]
    puts File.read("#{fixtures}/prerequisites/#{name}.json")
  elsif args.include?('get statefulset postgres')
    puts File.read("#{fixtures}/prerequisites/postgres.json")
  elsif args.include?('get configmap account-service-outbox-connector')
    puts read_fixture(fixtures, 'account-service-outbox-configmap')
  elsif args.include?('get configmap risk-service-outbox-connector')
    puts read_fixture(fixtures, 'risk-service-outbox-configmap')
  elsif args.include?('get pods -l app.kubernetes.io/name=kafka-connect')
    stage = File.exist?("#{root}/deleted") ? 'after-reassignment' : 'before'
    puts read_fixture(fixtures, "connect-pods-#{stage}")
  elsif args.include?('get pods -l app.kubernetes.io/name=postgres')
    emit(items: [ready_pod('postgres-0')])
  elsif args.include?('get pods -l app.kubernetes.io/name=kafka,')
    emit(items: [ready_pod('kafka-0')])
  elsif args.include?('get pod kafka-connect-a')
    if File.exist?("#{root}/deleted")
      warn 'Error from server (NotFound): pods "kafka-connect-a" not found'
      exit 1
    end
    pod = file_json("#{fixtures}/pod-pre-delete.json")
    pod['metadata']['uid'] = 'uid-drift' if mode == 'uid-drift'
    if File.exist?("#{root}/marked")
      pod['metadata']['labels']['simplematch.io/worker-loss-run'] = File.read("#{root}/marked")
    end
    emit(pod)
  elsif args.include?('patch pod kafka-connect-a')
    patch = JSON.parse(ARGV[ARGV.index('-p') + 1])
    abort 'missing UID precondition' unless patch.first == { 'op' => 'test', 'path' => '/metadata/uid', 'value' => 'uid-a' }
    File.write("#{root}/marked", patch.last.fetch('value'))
    pod = file_json("#{fixtures}/pod-pre-delete.json")
    pod['metadata']['labels']['simplematch.io/worker-loss-run'] = patch.last.fetch('value')
    emit(pod)
  elsif args.include?('get pods -l simplematch.io/worker-loss-run=')
    pod = file_json("#{fixtures}/pod-pre-delete.json")
    pod['metadata']['labels']['simplematch.io/worker-loss-run'] = File.read("#{root}/marked")
    emit(items: [pod])
  elsif args.include?('delete pods -l simplematch.io/worker-loss-run=')
    exit 42 if mode == 'delete-failed'
    File.write("#{root}/deleted", '')
    puts 'pod/kafka-connect-a deletion requested'
    puts 'pod/kafka-connect-b deleted' if mode == 'delete-output-mismatch'
  elsif args.include?('port-forward service/kafka-connect')
    File.write("#{root}/tunnel-pid", Process.pid.to_s)
    trap('TERM') { File.write("#{root}/tunnel-closed", ''); exit 0 }
    puts 'Forwarding from 127.0.0.1:38083 -> 8083'
    $stdout.flush
    sleep
  elsif args.include?('exec postgres-0')
    sql = ARGV[ARGV.index('--command') + 1]
    rows_path = "#{root}/rows.json"
    rows = File.exist?(rows_path) ? file_json(rows_path) : []
    if sql.include?('INSERT INTO')
      values = sql.match(/VALUES\s*\(\s*'([^']+)'::uuid, 'account.lifecycle', '([^']+)', NULL, decode\('([^']+)', 'hex'\),\s*'([^']+)', '([^']+)', 'account_reservation', '([^']+)',\s*(\d+),/m)
      abort 'unexpected outbox insert' unless values
      event, key, payload, type, headers, aggregate, time = values.captures
      rows << [event, 'account.lifecycle', key, 'NULL', payload, type, time, headers, 'account_reservation', aggregate]
      File.write(rows_path, JSON.generate(rows))
    elsif sql.include?('DELETE FROM')
      exit 42 if mode == 'cleanup-failed'
      File.write(rows_path, '[]')
      puts "BEGIN\nDELETE 2\n0\nCOMMIT"
    elsif sql.include?('SELECT event_id::text FROM')
      puts rows.map(&:first)
    else
      abort 'expected post-baseline exclusion' unless rows.length == 2 && sql.include?(rows.first.first)
      puts rows.last.join("\t")
    end
  elsif args.include?('exec kafka-0')
    if args.include?('kafka-topics.sh')
      topic = ARGV[ARGV.index('--topic') + 1]
      puts "Topic: #{topic} ReplicationFactor: 3"
    elsif args.include?('kafka-configs.sh')
      puts 'min.insync.replicas=2'
    elsif args.include?('kafka-get-offsets.sh')
      rows = file_json("#{root}/rows.json")
      puts "account.lifecycle:0:#{rows.length}"
    elsif args.include?('kafka-console-consumer.sh')
      row = file_json("#{root}/rows.json").last
      event, _, key, _, payload, type, time, headers = row
      separator = '__SIMPLEMATCH_CDC_HEADER__'
      if args.include?('print.value=true')
        puts [payload].pack('H*')
      elsif args.include?('print.key=true')
        puts key
      elsif !args.include?('print.partition=true') && args.include?('print.headers=true')
        puts ["id:#{event}", "eventType:#{type}", "headers_json:#{headers}",
          '__debezium.context.connectorLogicalName:simplematch-account-service',
          '__debezium.context.taskId:0', '__debezium.context.connectorName:postgresql',
          '__debezium.context.runId:11111111-2222-4333-8444-555555555555'].join(separator)
      elsif args.include?('print.timestamp=true')
        puts "CreateTime:#{time}\tPartition:0\tOffset:1"
      else
        puts "Partition:0\tOffset:1\tid:#{event}"
      end
    else
      abort "unexpected Kafka command: #{args}"
    end
  else
    exec("#{root}/base-kubectl", *ARGV)
  end
else
  abort "unexpected tool: #{tool}"
end
