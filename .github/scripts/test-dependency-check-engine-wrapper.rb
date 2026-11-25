#!/usr/bin/env ruby
require 'json'
require 'open3'
require 'rbconfig'
require 'rexml/document'
require 'tmpdir'

root = File.expand_path('../..', __dir__)
version = REXML::Document.new(File.read(File.join(root, 'pom.xml')))
  .root.elements['properties/dependency-check.plugin.version'].text

Dir.mktmpdir('engine-wrapper-contract.') do |directory|
  bin = File.join(directory, 'bin')
  scratch = File.join(directory, 'scratch with spaces')
  Dir.mkdir(bin)
  Dir.mkdir(scratch)
  executable = File.join(bin, 'mvn')
  File.write(executable, <<~RUBY)
    #!#{RbConfig.ruby}
    require 'json'
    pom = File.read(ARGV.fetch(ARGV.index('-f') + 1))
    File.write(ENV.fetch('ENGINE_WRAPPER_LOG'), JSON.generate('arguments' => ARGV, 'pom' => pom))
    exit Integer(ENV.fetch('ENGINE_WRAPPER_EXIT'))
  RUBY
  File.chmod(0o700, executable)
  [false, true].product([0, 29]).each do |offline, exit_code|
    log = File.join(directory, 'invocation.json')
    env = { 'PATH' => "#{bin}:#{ENV.fetch('PATH')}", 'RUNNER_TEMP' => scratch,
            'DEPENDENCY_CHECK_ENGINE_OFFLINE' => offline ? '1' : '0',
            'DEPENDENCY_CHECK_ENGINE_CLASSPATH' => nil, 'DEPENDENCY_CHECK_REPORT' => nil,
            'ENGINE_WRAPPER_LOG' => log, 'ENGINE_WRAPPER_EXIT' => exit_code.to_s }
    stdout, stderr, status = Open3.capture3(env, '/bin/bash',
      File.join(root, '.github/scripts/test-dependency-check-engine.sh'), chdir: directory)
    expected_status = exit_code.zero? ? 1 : exit_code
    abort 'Resolver failure was not propagated' unless status.exitstatus == expected_status
    output = stdout + stderr
    abort 'Missing classpath was not rejected' if exit_code.zero? && !output.include?('Engine classpath was not generated')
    abort 'Matcher ran without a resolved classpath' if output.include?('Real engine:') || output.include?('suppression mutation checks')
    abort 'Temporary SDK project leaked' unless Dir.children(scratch).empty?
    invocation = JSON.parse(File.read(log))
    arguments = invocation.fetch('arguments')
    abort 'Incorrect offline mode' unless arguments.include?('--offline') == offline
    abort 'Incorrect resolver goal' unless arguments.include?('org.apache.maven.plugins:maven-dependency-plugin:3.11.0:build-classpath')
    project_path = arguments.fetch(arguments.index('-f') + 1)
    expected_arguments = ['-B', '-ntp'] + (offline ? ['--offline'] : []) +
      ['-f', project_path, 'org.apache.maven.plugins:maven-dependency-plugin:3.11.0:build-classpath',
       "-Dmdep.outputFile=#{File.dirname(project_path)}/classpath.txt"]
    abort 'Unexpected resolver arguments' unless arguments == expected_arguments
    project = REXML::Document.new(invocation.fetch('pom')).root
    dependencies = project.get_elements('dependencies/dependency')
    dependency = dependencies.first
    abort 'SDK was not isolated at the pinned version' unless dependencies.length == 1 &&
      dependency.elements['groupId'].text.strip == 'org.owasp' &&
      dependency.elements['artifactId'].text.strip == 'dependency-check-core' &&
      dependency.elements['version'].text.strip == version.strip
  end
end
puts 'Passed 4 engine-wrapper failure scenarios, including offline mode and scratch cleanup'
