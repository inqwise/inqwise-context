#!/usr/bin/env ruby
require 'json'
require 'open3'
require 'rbconfig'
require 'rexml/document'
require 'tmpdir'
require 'yaml'

ROOT = File.expand_path('../..', __dir__)
WORKFLOW = YAML.safe_load(File.read(File.join(ROOT, '.github/workflows/ci.yml')))
POM = File.read(File.join(ROOT, 'pom.xml'))
SCAN_NAME = 'Run OWASP dependency check'
ADVISORY_NAME = 'Report OWASP test dependency findings'
REQUIRED_ARGS = %w[org.owasp:dependency-check-maven:check -B -DskipTestScope=true
                   -DskipProvidedScope=false -DskipRuntimeScope=false
                   -Dodc.outputDirectory=target/dependency-check-runtime
                   -DfailBuildOnCVSS=7 -Dformat=ALL].freeze
ADVISORY_ARGS = %w[org.owasp:dependency-check-maven:check -B -DskipTestScope=false
                   -DskipProvidedScope=false -DskipRuntimeScope=false
                   -Dodc.outputDirectory=target/dependency-check-all
                   -DsuppressionFiles=.github/dependency-check-suppressions.xml
                   -DfailBuildOnUnusedSuppressionRule=true -DfailBuildOnCVSS=7 -Dformat=ALL].freeze

def scanner_version_pinned?(source)
  root = REXML::Document.new(source).root
  plugins = REXML::XPath.match(root, 'build/pluginManagement/plugins/plugin')
  matches = plugins.select { |entry| entry.elements['groupId']&.text == 'org.owasp' && entry.elements['artifactId']&.text == 'dependency-check-maven' }
  plugin = matches.first
  matches.length == 1 && root.elements['properties/dependency-check.plugin.version']&.text == '13.0.0' &&
    plugin&.elements&.[]('version')&.text == '${dependency-check.plugin.version}'
end

def validate(workflow)
  errors = []
  jobs = workflow.fetch('jobs')
  job = jobs.fetch('dependency-check')
  errors << 'unconditional security job' if job.key?('if') || job['continue-on-error']
  errors << 'build waits for the blocking scan' unless jobs.fetch('build-and-publish').fetch('needs').include?('dependency-check')
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
  advisory_steps = steps.select { |step| step['name'] == ADVISORY_NAME }
  return ['exactly one advisory scan step'] unless advisory_steps.length == 1
  advisory = advisory_steps.first
  errors << 'always-run nonblocking advisory scan' unless advisory['if'] == 'always()' &&
    advisory['continue-on-error'] == true && advisory['shell'] == 'bash' && advisory['id'] == 'test-dependencies' &&
    advisory['env'] == scan['env'] && steps.index(advisory) > steps.index(scan)
  warning = steps.find { |step| step['name'] == 'Flag advisory scan failure' }
  errors << 'visible advisory failures' unless warning &&
    warning['if'] == "${{ always() && steps.test-dependencies.outcome == 'failure' }}" &&
    warning.fetch('run').include?('::warning::')
  upload = steps.find { |step| step['name'] == 'Upload OWASP dependency check results' }
  errors << 'retain reports after failure' unless upload && upload['if'] == 'always()' &&
    upload.fetch('with')['path'].lines.map(&:strip).sort ==
      %w[target/dependency-check-all/dependency-check-report.* target/dependency-check-runtime/dependency-check-report.*]
  quality = jobs.fetch('code-quality')
  command = 'ruby .github/scripts/test-security-workflow.rb'
  errors << 'unconditional CI contract check' if quality.key?('if') || quality['continue-on-error'] ||
    !quality.fetch('steps').any? { |step| step['run'] == command && !step.key?('if') && !step['continue-on-error'] }
  identity_command = 'ruby .github/scripts/test-dependency-check-suppressions.rb'
  errors << 'unconditional identity-correction check' unless quality.fetch('steps').any? do |step|
    step['run'] == identity_command && !step.key?('if') && !step['continue-on-error']
  end
  quality_steps = quality.fetch('steps')
  engine_steps = quality_steps.select { |step| step['run'] == 'bash .github/scripts/test-dependency-check-engine.sh' }
  engine = engine_steps.first
  setup_index = quality_steps.index { |step| step['name'] == 'Set up JDK 21' }
  cache_index = quality_steps.index { |step| step['name'] == 'Cache Maven dependencies' }
  errors << 'required real-engine check after JDK and cache setup' unless engine_steps.length == 1 &&
    !engine.key?('if') && !engine['continue-on-error'] && setup_index && cache_index &&
    quality_steps.index(engine) > setup_index && quality_steps.index(engine) > cache_index
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
    [[script, REQUIRED_ARGS], [advisory.fetch('run'), ADVISORY_ARGS]].each do |scan_script, required_args|
      errors << 'no expressions in shell source' if scan_script.include?('${{')
      _, _, syntax = Open3.capture3('/bin/bash', '-n', stdin_data: scan_script)
      errors << 'valid shell syntax' unless syntax.success?
      ['', 'synthetic-nvd-key-private'].product([0, 17]).each do |key, exit_code|
        log = File.join(directory, 'arguments.jsonl')
        File.write(log, '')
        env = { 'PATH' => "#{bin}:#{ENV.fetch('PATH')}", 'NVD_API_KEY' => key,
                'SECURITY_TEST_LOG' => log, 'SECURITY_TEST_EXIT' => exit_code.to_s }
        stdout, stderr, status = Open3.capture3(env, '/bin/bash', '--noprofile', '--norc', '-e', '-o', 'pipefail', '-c', scan_script)
        calls = File.readlines(log).map { |line| JSON.parse(line) }
        expected = required_args + (key.empty? ? [] : ['-DnvdApiKeyEnvironmentVariable=NVD_API_KEY'])
        errors << 'complete scan arguments in both key paths' unless calls.length == 1 && calls.first.sort == expected.sort
        errors << 'propagate scanner failures' unless status.exitstatus == exit_code
        errors << 'no secret in Maven arguments' if calls.flatten.any? { |argument| argument.include?('synthetic-nvd-key-private') }
        errors << 'no secret in output' if (stdout + stderr).include?('synthetic-nvd-key-private')
      end
    end
  end
  errors
end

abort 'Scanner version must match the reviewed 13.0.0 engine' unless scanner_version_pinned?(POM)
abort 'Unmanaged scanner version was accepted' if scanner_version_pinned?(POM.sub('<version>${dependency-check.plugin.version}</version>', ''))
abort 'Different scanner engine was accepted' if scanner_version_pinned?(POM.sub('<dependency-check.plugin.version>13.0.0</dependency-check.plugin.version>', '<dependency-check.plugin.version>13.1.0</dependency-check.plugin.version>'))
errors = validate(WORKFLOW)
abort "Security workflow contract failed: #{errors.join(', ')}" unless errors.empty?
mutations = []
mutations << ->(workflow) { workflow['jobs']['build-and-publish']['needs'].delete('dependency-check') }
%w[-DskipTestScope=true -DskipProvidedScope=false -DskipRuntimeScope=false
   -Dodc.outputDirectory=target/dependency-check-runtime -DfailBuildOnCVSS=7].each do |flag|
  [:index, :rindex].each do |position|
    mutations << lambda do |workflow|
      scan = workflow.fetch('jobs').fetch('dependency-check').fetch('steps').find { |step| step['name'] == SCAN_NAME }
      script = scan.fetch('run')
      script[script.public_send(position, flag), flag.length] = ''
    end
  end
end
ADVISORY_ARGS.reject { |argument| %w[org.owasp:dependency-check-maven:check -B].include?(argument) }.each do |flag|
  mutations << lambda do |workflow|
    step = workflow['jobs']['dependency-check']['steps'].find { |entry| entry['name'] == ADVISORY_NAME }
    step['run'] = step.fetch('run').sub(flag, '')
  end
end
mutations << lambda do |workflow|
  workflow['jobs']['dependency-check']['steps'].find { |step| step['name'] == ADVISORY_NAME }['continue-on-error'] = false
end
mutations << lambda do |workflow|
  workflow['jobs']['dependency-check']['steps'].find { |step| step['name'] == ADVISORY_NAME }['if'] = 'success()'
end
mutations << lambda do |workflow|
  workflow['jobs']['dependency-check']['steps'].reject! { |step| step['name'] == ADVISORY_NAME }
end
mutations << lambda do |workflow|
  workflow['jobs']['dependency-check']['steps'].reject! { |step| step['name'] == 'Flag advisory scan failure' }
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
mutations << lambda do |workflow|
  step = workflow['jobs']['code-quality']['steps'].find { |entry| entry['run'] == 'ruby .github/scripts/test-dependency-check-suppressions.rb' }
  step['if'] = 'false'
end
mutations << lambda do |workflow|
  workflow['jobs']['code-quality']['steps'].reject! { |step| step['run'] == 'ruby .github/scripts/test-dependency-check-suppressions.rb' }
end
mutations << lambda do |workflow|
  workflow['jobs']['code-quality']['steps'].find { |step| step['run'] == 'bash .github/scripts/test-dependency-check-engine.sh' }['continue-on-error'] = true
end
mutations << lambda do |workflow|
  workflow['jobs']['code-quality']['steps'].find { |step| step['run'] == 'bash .github/scripts/test-dependency-check-engine.sh' }['if'] = 'false'
end
mutations << lambda do |workflow|
  workflow['jobs']['code-quality']['steps'].reject! { |step| step['run'] == 'bash .github/scripts/test-dependency-check-engine.sh' }
end
mutations << lambda do |workflow|
  steps = workflow['jobs']['code-quality']['steps']
  engine = steps.find { |step| step['run'] == 'bash .github/scripts/test-dependency-check-engine.sh' }
  steps.delete(engine)
  steps.unshift(engine)
end
mutations.each_with_index do |mutate, index|
  candidate = Marshal.load(Marshal.dump(WORKFLOW))
  mutate.call(candidate)
  abort "Broken security workflow mutation #{index + 1} was accepted" if validate(candidate).empty?
end
puts "Passed 8 synthetic scanner scenarios and #{mutations.length} rejected security workflow mutations"
stdout, stderr, status = Open3.capture3(RbConfig.ruby, File.join(__dir__, 'test-dependency-check-engine-wrapper.rb'))
abort "Engine-wrapper contract failed:\n#{stdout}#{stderr}" unless status.success?
puts stdout
