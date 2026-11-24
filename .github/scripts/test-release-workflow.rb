#!/usr/bin/env ruby
require 'yaml'
require 'open3'
require 'shellwords'

ROOT = File.expand_path('../..', __dir__)
RELEASE = YAML.safe_load(File.read(File.join(ROOT, '.github/workflows/release.yml')))
CI = YAML.safe_load(File.read(File.join(ROOT, '.github/workflows/ci.yml')))
SOURCE = '${{ runner.temp }}/inqwise-release-source'
VERSION = '${{ steps.version.outputs.release_version }}'
TAG = '${{ steps.version.outputs.release_tag }}'
COMMIT = '${{ steps.source.outputs.release_commit }}'
GUARD = 'bash "$GITHUB_WORKSPACE/.github/scripts/verify-release-source.sh"'

def validate(workflow)
  steps = workflow.fetch('jobs').fetch('release').fetch('steps')
  errors = []
  concurrency = workflow['concurrency']
  expected_concurrency = {
    'group' => 'inqwise-context-release', 'cancel-in-progress' => false, 'queue' => 'max'
  }
  errors << 'serialized non-cancelling release queue' unless concurrency == expected_concurrency
  errors << 'no job-level concurrency override' if workflow.fetch('jobs').fetch('release').key?('concurrency')
  find = lambda do |name|
    matches = steps.select { |step| step['name'] == name }
    raise "Expected exactly one #{name} step" unless matches.length == 1
    matches.first
  end
  resolve = find.call('Resolve release version')
  credentials = find.call('Verify release credentials')
  setup = find.call('Set up JDK 21')
  configure = find.call('Configure Git user')
  prepare = find.call('Maven Release (Manual Trigger)')
  create = find.call('Create detached release source')
  source = find.call('Verify tagged release source')
  build = find.call('Build and test tagged release')
  deploy = find.call('Deploy to Maven Central')
  assets = find.call('Prepare Release Assets')
  manual = find.call('Create GitHub Release (Manual Trigger)')
  existing = find.call('Upload Release Assets (Existing Release)')
  ordered = [resolve, credentials, setup, configure, prepare, create, source, build, deploy, assets, manual, existing]
  errors << 'release step order' unless ordered.map { |step| steps.index(step) }.each_cons(2).all? { |a, b| a < b }
  errors << 'credential preflight command' unless credentials['run'] == 'bash .github/scripts/verify-release-credentials.sh'
  errors << 'unconditional credential preflight' if credentials.key?('if')
  errors << 'fail-closed credential preflight' if credentials['continue-on-error']
  expected_credentials = {
    'MAVEN_USERNAME' => '${{ secrets.SONATYPE_USERNAME }}',
    'MAVEN_PASSWORD' => '${{ secrets.SONATYPE_PASSWORD }}',
    'GPG_PRIVATE_KEY' => '${{ secrets.GPG_PRIVATE_KEY }}'
  }
  errors << 'credential secret bindings' unless credentials['env'] == expected_credentials
  expected_prepare = %w[mvn -B -ntp release:clean release:prepare release:perform] + [
    '-DreleaseVersion=$RELEASE_VERSION', '-DdevelopmentVersion=$DEVELOPMENT_VERSION',
    '-Dtag=$RELEASE_TAG', '-DskipTests',
    '-Darguments=-B -ntp -Dmaven.deploy.skip=true -DskipTests'
  ]
  # Remove shell line continuations before parsing argument boundaries, not values.
  prepare_arguments = Shellwords.shellsplit(prepare.fetch('run').gsub("\\\n", ''))
  errors << 'non-interactive release arguments' unless prepare_arguments == expected_prepare
  expected_prepare_env = {
    'RELEASE_VERSION' => VERSION, 'RELEASE_TAG' => TAG,
    'DEVELOPMENT_VERSION' => '${{ steps.version.outputs.development_version }}'
  }
  expected_prepare_env.each do |name, value|
    errors << 'validated preparation inputs' unless prepare.fetch('env')[name] == value
  end
  errors << 'resolver output identity' unless resolve['id'] == 'version'
  errors << 'source output identity' unless source['id'] == 'source'
  errors << 'detached tagged worktree' unless create['run'] == <<~SH
    COMMIT=$(git rev-parse --verify "refs/tags/$RELEASE_TAG^{commit}")
    git worktree add --detach "$RUNNER_TEMP/inqwise-release-source" "$COMMIT"
  SH
  errors << 'worktree tag binding' unless create.fetch('env')['RELEASE_TAG'] == TAG
  [source, build, deploy].each do |step|
    errors << 'tagged working directory' unless step['working-directory'] == SOURCE
    errors << 'unconditional tagged source checks' if step.key?('if') && step != deploy
    errors << 'fail-closed source checks' if step['continue-on-error']
  end
  errors << 'initial source guard' unless source['run'] == GUARD
  event_pin = "${{ github.event_name == 'release' && github.sha || '' }}"
  errors << 'published release event commit pin' unless source.fetch('env')['RELEASE_COMMIT'] == event_pin
  errors << 'tagged tests' unless build['run'] == 'mvn -B -ntp clean verify'
  errors << 'pre-deploy source guard' unless deploy.fetch('run').lines.first.strip == GUARD
  expected_deploy = %w[mvn -B -ntp clean deploy -P sonatype-oss-release -Dmaven.deploy.skip=true -DskipTests] +
                    ['-DreleaseVersion=$RELEASE_VERSION']
  deployment_lines = deploy.fetch('run').lines.drop(1).join
  errors << 'non-interactive deployment arguments' unless Shellwords.shellsplit(deployment_lines) == expected_deploy
  errors << 'verified commit pin' unless deploy.fetch('env')['RELEASE_COMMIT'] == COMMIT
  [source, deploy].each do |step|
    errors << 'source version/tag binding' unless step.fetch('env').values_at('RELEASE_VERSION', 'RELEASE_TAG') == [VERSION, TAG]
  end
  errors << 'asset collector' unless assets['run'] == 'bash .github/scripts/prepare-release-assets.sh'
  errors << 'tagged artifact directory' unless assets.fetch('env')['RELEASE_BUILD_DIR'] == "#{SOURCE}/target"
  errors << 'artifact version' unless assets.fetch('env')['RELEASE_VERSION'] == VERSION
  errors << 'GitHub release commit' unless manual.fetch('with')['target_commitish'] == COMMIT
  expected = ['', '-sources', '-javadoc'].flat_map do |suffix|
    path = "release-assets/inqwise-context-#{VERSION}#{suffix}.jar"
    [path, "#{path}.asc"]
  end
  [manual, existing].each do |step|
    options = step.fetch('with')
    errors << 'release tag binding' unless options['tag_name'] == TAG
    paths = options.fetch('files').lines.map(&:strip).reject(&:empty?)
    errors << 'exact six assets' unless paths.sort == expected.sort
    errors << 'missing assets must fail' unless options['fail_on_unmatched_files'] == true
  end
  steps.each do |step|
    next unless step['run']
    errors << 'no expressions in shell commands' if step['run'].include?('${{')
    _, _, status = Open3.capture3('bash', '-n', stdin_data: step['run'])
    errors << 'shell syntax' unless status.success?
  end
  errors
end

errors = validate(RELEASE)
abort "Release workflow contract failed: #{errors.join(', ')}" unless errors.empty?
ci_steps = CI.fetch('jobs').fetch('code-quality').fetch('steps')
%w[version credentials source assets].each do |name|
  command = "bash .github/scripts/test-release-#{name}.sh"
  abort "Missing CI regression command: #{command}" unless ci_steps.any? { |step| step['run'] == command && !step['if'] && !step['continue-on-error'] }
end
command = 'ruby .github/scripts/test-release-workflow.rb'
abort 'Missing workflow regression CI step' unless ci_steps.any? { |step| step['run'] == command && !step['if'] && !step['continue-on-error'] }

# Mutations prove the assertions reject broken wiring, not just valid YAML.
mutations = [
  ['Resolve release version', 'id', 'wrong'],
  ['Verify release credentials', 'run', 'true'],
  ['Verify release credentials', 'if', 'false'],
  ['Verify release credentials', 'continue-on-error', true],
  ['Verify tagged release source', 'id', 'wrong'],
  ['Create detached release source', 'run', 'git checkout "$RELEASE_TAG"'],
  ['Verify tagged release source', 'working-directory', '.'],
  ['Verify tagged release source', 'run', 'true'],
  ['Verify tagged release source', 'if', 'false'],
  ['Verify tagged release source', 'continue-on-error', true],
  ['Build and test tagged release', 'run', 'mvn package -DskipTests'],
  ['Build and test tagged release', 'working-directory', '.'],
  ['Deploy to Maven Central', 'run', 'mvn deploy'],
  ['Deploy to Maven Central', 'working-directory', '.'],
  ['Prepare Release Assets', 'run', 'true']
]
nested = [
  ['Verify release credentials', 'env', 'MAVEN_USERNAME', 'wrong'],
  ['Verify release credentials', 'env', 'MAVEN_PASSWORD', 'wrong'],
  ['Verify release credentials', 'env', 'GPG_PRIVATE_KEY', 'wrong'],
  ['Maven Release (Manual Trigger)', 'env', 'RELEASE_VERSION', 'wrong'],
  ['Maven Release (Manual Trigger)', 'env', 'RELEASE_TAG', 'wrong'],
  ['Maven Release (Manual Trigger)', 'env', 'DEVELOPMENT_VERSION', 'wrong'],
  ['Create detached release source', 'env', 'RELEASE_TAG', 'wrong'],
  ['Verify tagged release source', 'env', 'RELEASE_VERSION', 'wrong'],
  ['Verify tagged release source', 'env', 'RELEASE_COMMIT', ''],
  ['Verify tagged release source', 'env', 'RELEASE_COMMIT', '${{ github.sha }}'],
  ['Verify tagged release source', 'env', 'RELEASE_COMMIT', '${{ github.event.release.target_commitish }}'],
  ['Deploy to Maven Central', 'env', 'RELEASE_COMMIT', 'HEAD'],
  ['Deploy to Maven Central', 'env', 'RELEASE_TAG', 'wrong'],
  ['Prepare Release Assets', 'env', 'RELEASE_BUILD_DIR', 'target'],
  ['Prepare Release Assets', 'env', 'RELEASE_VERSION', 'wrong'],
  ['Create GitHub Release (Manual Trigger)', 'with', 'target_commitish', 'main']
]
['Create GitHub Release (Manual Trigger)', 'Upload Release Assets (Existing Release)'].each do |name|
  nested.concat([[name, 'with', 'files', 'target/*.jar'], [name, 'with', 'tag_name', 'wrong'],
                 [name, 'with', 'fail_on_unmatched_files', false]])
end
checks = mutations.map do |name, field, value|
  ->(steps) { steps.find { |step| step['name'] == name }[field] = value }
end
checks.concat(nested.map do |name, field, key, value|
  ->(steps) { steps.find { |step| step['name'] == name }.fetch(field)[key] = value }
end)
checks << ->(steps) { steps.reverse! }
checks << lambda do |steps|
  preflight = steps.find { |step| step['name'] == 'Verify release credentials' }
  steps.delete(preflight)
  steps << preflight
end
checks << ->(steps) { steps.first['run'] = 'echo ${{ inputs.version }}' }
checks << ->(steps) { steps.first['run'] = 'if broken' }
['Maven Release (Manual Trigger)', 'Deploy to Maven Central'].each do |name|
  ['-B ', '-ntp '].each do |flag|
    checks << lambda do |steps|
      step = steps.find { |entry| entry['name'] == name }
      step['run'] = step.fetch('run').sub(flag, '')
    end
  end
end
checks << lambda do |steps|
  step = steps.find { |entry| entry['name'] == 'Maven Release (Manual Trigger)' }
  step['run'] = step.fetch('run').sub('-Darguments="-B -ntp ', '-Darguments="')
end
checks.each_with_index do |mutate, index|
  candidate = Marshal.load(Marshal.dump(RELEASE))
  mutate.call(candidate.fetch('jobs').fetch('release').fetch('steps'))
  abort "Broken workflow mutation #{index + 1} was accepted" if validate(candidate).empty?
end
queue_mutations = [
  ->(workflow) { workflow.delete('concurrency') },
  ->(workflow) { workflow['concurrency'] = 'inqwise-context-release' },
  ->(workflow) { workflow['concurrency']['group'] = '${{ github.ref }}' },
  ->(workflow) { workflow['concurrency']['cancel-in-progress'] = true },
  ->(workflow) { workflow['concurrency'].delete('cancel-in-progress') },
  ->(workflow) { workflow['concurrency']['queue'] = 'single' },
  ->(workflow) { workflow['concurrency'].delete('queue') },
  ->(workflow) { workflow.fetch('jobs').fetch('release')['concurrency'] = 'another-group' }
]
queue_mutations.each_with_index do |mutate, index|
  candidate = Marshal.load(Marshal.dump(RELEASE))
  mutate.call(candidate)
  abort "Broken release queue mutation #{index + 1} was accepted" if validate(candidate).empty?
end
puts "Passed release workflow contract and #{checks.length + queue_mutations.length} rejected wiring mutations"
