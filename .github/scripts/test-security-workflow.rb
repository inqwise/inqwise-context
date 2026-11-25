#!/usr/bin/env ruby
require 'json'
require 'open3'
require 'rbconfig'
require 'tmpdir'
require 'yaml'

ROOT = File.expand_path('../..', __dir__)
WORKFLOW = YAML.safe_load(File.read(File.join(ROOT, '.github/workflows/ci.yml')))
SCAN_NAME = 'Run OWASP dependency check'
REQUIRED_ARGS = %w[org.owasp:dependency-check-maven:check -B -DskipTestScope=false
                   -DskipProvidedScope=false -DskipRuntimeScope=false -DfailBuildOnCVSS=7 -Dformat=ALL].freeze

def validate(workflow)
  errors = []
  jobs = workflow.fetch('jobs')
  job = jobs.fetch('dependency-check')
  errors << 'unconditional security job' if job.key?('if') || job['continue-on-error']
  steps = job.fetch('steps')
  scans = steps.select { |step| step['name'] == SCAN_NAME }
  return ['exactly one security scan step'] unless scans.length == 1
  scan = scans.first
  errors << 'explicit fail-fast Bash shell' unless scan['shell'] == 'bash'
  errors << 'fail-closed unconditional scan' if scan.key?('if') || scan['continue-on-error']
  errors << 'secret passed through environment' unless scan['env'] == { 'NVD_API_KEY' => '${{ secrets.NVD_API_KEY }}' }
  script = scan.fetch('run')
  errors << 'no expressions in shell source' if script.include?('${{')
  _, _, syntax = Open3.capture3('/bin/bash', '-n', stdin_data: script)
  errors << 'valid shell syntax' unless syntax.success?
  upload = steps.find { |step| step['name'] == 'Upload OWASP dependency check results' }
  errors << 'retain reports after failure' unless upload && upload['if'] == 'always()' &&
    upload.fetch('with')['path'] == 'target/dependency-check-report.*'
  quality = jobs.fetch('code-quality')
  command = 'ruby .github/scripts/test-security-workflow.rb'
  errors << 'unconditional CI contract check' if quality.key?('if') || quality['continue-on-error'] ||
    !quality.fetch('steps').any? { |step| step['run'] == command && !step.key?('if') && !step['continue-on-error'] }
  return errors unless errors.empty?

  Dir.mktmpdir('security-workflow-test.') do |directory|
    bin = File.join(directory, 'bin')
    Dir.mkdir(bin)
    executable = File.join(bin, 'mvn')
    # Record argv instead of invoking Maven, a scanner, or any network service.
    File.write(executable, <<~RUBY)
      #!#{RbConfig.ruby}
      require 'json'
      File.open(ENV.fetch('SECURITY_TEST_LOG'), 'a') { |file| file.puts(JSON.generate(ARGV)) }
      exit Integer(ENV.fetch('SECURITY_TEST_EXIT'))
    RUBY
    File.chmod(0o700, executable)
    ['', 'synthetic-nvd-key-private'].product([0, 17]).each do |key, exit_code|
      log = File.join(directory, 'arguments.jsonl')
      File.write(log, '')
      env = { 'PATH' => "#{bin}:#{ENV.fetch('PATH')}", 'NVD_API_KEY' => key,
              'SECURITY_TEST_LOG' => log, 'SECURITY_TEST_EXIT' => exit_code.to_s }
      stdout, stderr, status = Open3.capture3(env, '/bin/bash', '--noprofile', '--norc', '-e', '-o', 'pipefail', '-c', script)
      calls = File.readlines(log).map { |line| JSON.parse(line) }
      expected = REQUIRED_ARGS + (key.empty? ? [] : ['-DnvdApiKeyEnvironmentVariable=NVD_API_KEY'])
      errors << 'complete scan arguments in both key paths' unless calls.length == 1 && calls.first.sort == expected.sort
      errors << 'propagate scanner failures' unless status.exitstatus == exit_code
      errors << 'no secret in Maven arguments' if calls.flatten.any? { |argument| argument.include?('synthetic-nvd-key-private') }
      errors << 'no secret in output' if (stdout + stderr).include?('synthetic-nvd-key-private')
    end
  end
  errors
end

errors = validate(WORKFLOW)
abort "Security workflow contract failed: #{errors.join(', ')}" unless errors.empty?
mutations = []
%w[-DskipTestScope=false -DskipProvidedScope=false -DskipRuntimeScope=false -DfailBuildOnCVSS=7].each do |flag|
  [:index, :rindex].each do |position|
    mutations << lambda do |workflow|
      scan = workflow.fetch('jobs').fetch('dependency-check').fetch('steps').find { |step| step['name'] == SCAN_NAME }
      script = scan.fetch('run')
      script[script.public_send(position, flag), flag.length] = ''
    end
  end
end
mutations << ->(workflow) { workflow['jobs']['dependency-check']['continue-on-error'] = true }
mutations << ->(workflow) { workflow['jobs']['dependency-check']['if'] = 'false' }
mutations << lambda do |workflow|
  workflow['jobs']['dependency-check']['steps'].find { |step| step['name'] == SCAN_NAME }['continue-on-error'] = true
end
mutations << lambda do |workflow|
  workflow['jobs']['dependency-check']['steps'].find { |step| step['name'] == 'Upload OWASP dependency check results' }['if'] = 'success()'
end
mutations << lambda do |workflow|
  scan = workflow['jobs']['dependency-check']['steps'].find { |step| step['name'] == SCAN_NAME }
  scan['run'] = scan.fetch('run').sub('-DnvdApiKeyEnvironmentVariable=NVD_API_KEY', '-DnvdApiKey="$NVD_API_KEY"')
end
mutations << lambda do |workflow|
  workflow['jobs']['dependency-check']['steps'].find { |step| step['name'] == SCAN_NAME }['shell'] = 'bash {0}'
end
mutations << lambda do |workflow|
  workflow['jobs']['dependency-check']['steps'].find { |step| step['name'] == SCAN_NAME }.delete('shell')
end
mutations.each_with_index do |mutate, index|
  candidate = Marshal.load(Marshal.dump(WORKFLOW))
  mutate.call(candidate)
  abort "Broken security workflow mutation #{index + 1} was accepted" if validate(candidate).empty?
end
puts "Passed 4 synthetic scanner scenarios and #{mutations.length} rejected security workflow mutations"
