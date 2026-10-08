#!/usr/bin/env ruby
# frozen_string_literal: true

require 'fileutils'
require 'json'
require 'minitest/autorun'
require 'open3'
require 'tmpdir'

# Exercise the real command entry point and real manager, replacing only external
# CLIs. No host Docker/kind executable is reachable from the test's isolated PATH.
class HardResetSafetyTest < Minitest::Test
  ROOT = File.expand_path('..', __dir__)
  FIXTURES = File.join(__dir__, 'testdata/hard-reset')

  JSON.parse(File.read(File.join(FIXTURES, 'baseline.json'))).each do |expected|
    define_method("test_#{expected.fetch('scenario').tr('-', '_')}") do
      Dir.mktmpdir('simplematch-hard-reset-') do |sandbox|
        scripts = File.join(sandbox, 'scripts')
        bin = File.join(sandbox, 'bin')
        FileUtils.mkdir_p([scripts, bin, File.join(sandbox, '.git')])
        FileUtils.cp_r(File.join(ROOT, 'scripts/lib'), scripts)
        %w[hard-reset-local.sh manage-simplematch-live.sh manage-local-registry.sh
           build-local-images.sh].each do |name|
          FileUtils.cp(File.join(ROOT, 'scripts', name), scripts)
        end
        %w[bash dirname grep].each do |name|
          executable = ENV.fetch('PATH').split(File::PATH_SEPARATOR)
                          .map { |path| File.join(path, name) }
                          .find { |path| File.executable?(path) && !File.directory?(path) }
          refute_nil executable, "missing test prerequisite: #{name}"
          FileUtils.ln_s(executable, File.join(bin, name))
        end
        %w[docker kind kubectl jq findmnt].each do |name|
          next if name == 'kind' && %w[missing-kind observation-failed].include?(expected.fetch('scenario'))

          target = File.join(bin, name)
          FileUtils.cp(File.join(FIXTURES, 'command.sh'), target)
          FileUtils.chmod(0o755, target)
        end
        state = File.join(sandbox, 'state')
        calls = File.join(sandbox, 'calls')
        File.write(state, expected.fetch('scenario') == 'absent' ? 'absent' : '')
        File.write(calls, '')
        environment = {
          'PATH' => bin, 'HOME' => sandbox,
          'HARD_RESET_SCENARIO' => expected.fetch('scenario'),
          'HARD_RESET_STATE' => state, 'HARD_RESET_CALLS' => calls
        }
        arguments = %w[--yes --keep-project-build-state --keep-registry-cache]
        arguments << '--dry-run' if expected.fetch('scenario') == 'dry-run'
        output, status = Open3.capture2e(environment, File.join(bin, 'bash'),
                                        File.join(scripts, 'hard-reset-local.sh'),
                                        *arguments, unsetenv_others: true)
        actual = { 'status' => status.exitstatus,
                   'mutations' => File.readlines(calls, chomp: true) }
        assert_equal expected.slice('status', 'mutations'), actual, output
        assert_includes output, expected.fetch('message')
      end
    end
  end
end
