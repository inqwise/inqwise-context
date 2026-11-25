#!/usr/bin/env bash
set -euo pipefail

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
scratch=$(mktemp -d "${RUNNER_TEMP:-${TMPDIR:-/tmp}}/dependency-check-engine-test.XXXXXX")
trap 'rm -rf "$scratch"' EXIT

# Resolve the SDK in a separate project, never on the library's classpath.
ruby -rrexml/document - "$root/pom.xml" "$scratch/pom.xml" <<'RUBY'
source = REXML::Document.new(File.read(ARGV.fetch(0))).root
version = source.elements['properties/dependency-check.plugin.version']&.text
abort 'Expected a pinned scanner version' unless version && version.match?(/\A[0-9]+\.[0-9]+\.[0-9]+\z/)
document = REXML::Document.new
project = document.add_element('project', 'xmlns' => 'http://maven.apache.org/POM/4.0.0')
{ 'modelVersion' => '4.0.0', 'groupId' => 'com.inqwise.validation',
  'artifactId' => 'dependency-check-engine-validation', 'version' => '1' }.each do |name, value|
  project.add_element(name).text = value
end
dependency = project.add_element('dependencies').add_element('dependency')
{ 'groupId' => 'org.owasp', 'artifactId' => 'dependency-check-core', 'version' => version }.each do |name, value|
  dependency.add_element(name).text = value
end
File.open(ARGV.fetch(1), 'w') { |file| document.write(file, 2) }
RUBY

arguments=(-B -ntp)
if [[ "${DEPENDENCY_CHECK_ENGINE_OFFLINE:-0}" == 1 ]]; then
  arguments+=(--offline)
fi
mvn "${arguments[@]}" -f "$scratch/pom.xml" \
  org.apache.maven.plugins:maven-dependency-plugin:3.11.0:build-classpath \
  "-Dmdep.outputFile=$scratch/classpath.txt"
[[ -s "$scratch/classpath.txt" ]] || { printf '%s\n' 'Engine classpath was not generated' >&2; exit 1; }
DEPENDENCY_CHECK_ENGINE_CLASSPATH="$(< "$scratch/classpath.txt")" \
  ruby "$root/.github/scripts/test-dependency-check-suppressions.rb"
